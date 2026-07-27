package com.ethosprotocol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ethosprotocol.services.VaultDeepLink
import com.ethosprotocol.services.VaultDeepLinkParser
import com.ethosprotocol.ui.screens.AuthScreen
import com.ethosprotocol.ui.screens.BeneficiaryAcceptanceScreen
import com.ethosprotocol.ui.screens.DepositScreen
import com.ethosprotocol.ui.screens.VaultDeepLinkScreen
import com.ethosprotocol.ui.screens.VaultListScreen
import com.ethosprotocol.ui.screens.WithdrawScreen
import com.ethosprotocol.ui.theme.EthosProtocolTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    // DeepLinkViewModel is scoped to this Activity and backed by SavedStateHandle, so both
    // pending deep-link fields survive configuration changes and process death/recreation.
    // Previously these were plain mutableStateOf fields on the Activity itself, which meant
    // a deep-link tap during authentication would be silently lost on process recreation. (#93)
    private val deepLinkViewModel: DeepLinkViewModel by viewModels()

    private var showPermissionRationale by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled gracefully — denial does not break the app */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only handle the launch intent on a fresh start (savedInstanceState == null).
        // On recreation (config change or process death), SavedStateHandle already holds
        // the pending state — re-parsing the original launch intent would overwrite it.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            EthosProtocolTheme {
                val beneficiaryAcceptVaultId by deepLinkViewModel.pendingBeneficiaryAcceptVaultId
                    .collectAsStateWithLifecycle()
                val vaultDeepLink by deepLinkViewModel.pendingVaultDeepLink
                    .collectAsStateWithLifecycle()

                NotificationPermissionEffect(
                    showRationale = showPermissionRationale,
                    onRationaleShown = { showPermissionRationale = false },
                    onRequestPermission = {
                        showPermissionRationale = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                AppNavigation(
                    beneficiaryAcceptVaultId = beneficiaryAcceptVaultId,
                    vaultDeepLink = vaultDeepLink,
                    onBeneficiaryAcceptConsumed = { deepLinkViewModel.consumeBeneficiaryAccept() },
                    onVaultDeepLinkConsumed = { deepLinkViewModel.consumeVaultDeepLink() }
                )
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    internal fun handleIncomingIntent(intent: Intent) {
        intent.data?.let { uri ->
            VaultDeepLinkParser.parse(uri)?.let {
                deepLinkViewModel.setPendingVaultDeepLink(it)
                deepLinkViewModel.setPendingBeneficiaryAccept(null)
                return
            }
        }
        extractBeneficiaryAcceptVaultId(intent)?.let {
            deepLinkViewModel.setPendingBeneficiaryAccept(it)
            deepLinkViewModel.setPendingVaultDeepLink(null)
        }
    }

    private fun extractBeneficiaryAcceptVaultId(intent: Intent): String? =
        intent.data
            ?.takeIf { it.scheme == "https" && it.host == "ethos-protocol.app" && it.path == "/accept" }
            ?.getQueryParameter("vault_id")
            // The activity is exported and this intent-filter accepts explicit intents from any
            // app, not just verified browser navigations — validate before it flows into an API
            // path (apiClient.acceptBeneficiary) or a navigation route.
            ?.takeIf { VaultDeepLinkParser.isValidVaultId(it) }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        when {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> Unit
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                showPermissionRationale = true
            else ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun NotificationPermissionEffect(
    showRationale: Boolean,
    onRationaleShown: () -> Unit,
    onRequestPermission: () -> Unit
) {
    if (showRationale) {
        AlertDialog(
            onDismissRequest = onRationaleShown,
            title = { Text("Stay Notified") },
            text = {
                Text(
                    "Ethos-Protocol needs notification permission to alert you before your vault " +
                    "expires and remind you to check in. Without it, reminders will not be delivered."
                )
            },
            confirmButton = {
                TextButton(onClick = onRequestPermission) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = onRationaleShown) { Text("Not now") }
            }
        )
    }
}

@Composable
private fun AppNavigation(
    beneficiaryAcceptVaultId: String?,
    vaultDeepLink: VaultDeepLink?,
    onBeneficiaryAcceptConsumed: () -> Unit,
    onVaultDeepLinkConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(authState.isAuthenticated) {
        if (authState.isAuthenticated) navController.navigate("vaults") { popUpTo("auth") { inclusive = true } }
        else navController.navigate("auth") { popUpTo("vaults") { inclusive = true } }
    }

    LaunchedEffect(beneficiaryAcceptVaultId, authState.isAuthenticated) {
        if (beneficiaryAcceptVaultId != null && authState.isAuthenticated) {
            navController.navigate("accept/$beneficiaryAcceptVaultId")
            onBeneficiaryAcceptConsumed()
        }
    }

    LaunchedEffect(vaultDeepLink, authState.isAuthenticated) {
        if (vaultDeepLink != null && authState.isAuthenticated) {
            navController.navigate(
                "vault/${vaultDeepLink.vaultId}/${vaultDeepLink.action.pathSegment}"
            )
            onVaultDeepLinkConsumed()
        }
    }

    NavHost(navController, startDestination = if (authState.isAuthenticated) "vaults" else "auth") {
        composable("auth") { AuthScreen(vm = authVm) }
        composable("vaults") {
            VaultListScreen(onVaultClick = { /* navigate to detail */ })
        }
        composable("accept/{vaultId}") { backStack ->
            val vaultId = backStack.arguments?.getString("vaultId") ?: return@composable
            BeneficiaryAcceptanceScreen(
                vaultId = vaultId,
                onAccepted = { navController.popBackStack() },
                onDecline = { navController.popBackStack() }
            )
        }
        composable("vault/{vaultId}/{action}") { backStack ->
            val vaultId = backStack.arguments?.getString("vaultId") ?: return@composable
            val action = backStack.arguments?.getString("action") ?: return@composable
            VaultDeepLinkScreen(
                vaultId = vaultId,
                actionPath = action,
                onDone = { navController.popBackStack() },
                onDeposit = { id -> navController.navigate("deposit/$id") },
                onWithdraw = { id -> navController.navigate("withdraw/$id/0") }
            )
        }
        // Deposit route: reached from VaultDeepLinkScreen (deposit action) or directly.
        composable("deposit/{vaultId}") { backStack ->
            val vaultId = backStack.arguments?.getString("vaultId") ?: return@composable
            DepositScreen(
                vaultId = vaultId,
                onDone = { navController.popBackStack() }
            )
        }
        // Withdraw route: vaultBalance is passed as a stroop-encoded Long string so the
        // WithdrawScreen can enforce the client-side balance guard without a separate
        // ViewModel load. The deep-link entry point passes 0 (balance unknown from the
        // push notification context); the UI displays the field but the server always
        // enforces the real balance server-side.
        composable("withdraw/{vaultId}/{vaultBalance}") { backStack ->
            val vaultId = backStack.arguments?.getString("vaultId") ?: return@composable
            val vaultBalance = backStack.arguments?.getString("vaultBalance")?.toLongOrNull() ?: 0L
            WithdrawScreen(
                vaultId = vaultId,
                vaultBalanceStroops = vaultBalance,
                onDone = { navController.popBackStack() }
            )
        }
    }
}
