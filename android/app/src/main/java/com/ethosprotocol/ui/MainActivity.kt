package com.ethosprotocol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ethosprotocol.security.AppLifecycleObserver
import com.ethosprotocol.security.SignatureResult
import com.ethosprotocol.security.SignatureVerifier
import com.ethosprotocol.security.TamperWarningDialog
import com.ethosprotocol.services.BiometricHelper
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

    // #278: Whether to show the tamper-warning dialog (APK signature mismatch).
    private var showTamperWarning by mutableStateOf(false)

    private val authVm: AuthViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled gracefully — denial does not break the app */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // #276: Register the process-level lifecycle observer so SessionLockManager
        // receives foreground/background transitions for the whole app process.
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())

        // Only handle the launch intent on a fresh start (savedInstanceState == null).
        // On recreation (config change or process death), SavedStateHandle already holds
        // the pending state — re-parsing the original launch intent would overwrite it.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            EthosProtocolTheme {
                val beneficiaryAccept by deepLinkViewModel.pendingBeneficiaryAccept
                    .collectAsStateWithLifecycle()
                val vaultDeepLink by deepLinkViewModel.pendingVaultDeepLink
                    .collectAsStateWithLifecycle()

                // #278: Show tamper-warning dialog if the APK signing cert doesn't match.
                if (showTamperWarning) {
                    TamperWarningDialog(onDismiss = { showTamperWarning = false })
                }

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
                    beneficiaryAccept = beneficiaryAccept,
                    vaultDeepLink = vaultDeepLink,
                    onBeneficiaryAcceptConsumed = { deepLinkViewModel.consumeBeneficiaryAccept() },
                    onVaultDeepLinkConsumed = { deepLinkViewModel.consumeVaultDeepLink() }
                )
            }
        }

        // #277: Prevent sensitive content from appearing in the system app-switcher
        // thumbnail or being captured by screenshots / screen recordings.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // #278: Check APK signing certificate. Show a non-blocking warning if the
        // app has been repackaged/sideloaded. Consistent with #118 (jailbreak warning).
        if (SignatureVerifier().verify(this) is SignatureResult.Mismatch) {
            showTamperWarning = true
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
        extractBeneficiaryAccept(intent)?.let {
            deepLinkViewModel.setPendingBeneficiaryAccept(it)
            deepLinkViewModel.setPendingVaultDeepLink(null)
        }
    }

    // Returns (vaultId, token) parsed from https://ethos-protocol.app/vaults/{id}/accept?token={token}.
    // Both values are validated by the parser; null is returned if either is missing or invalid.
    private fun extractBeneficiaryAccept(intent: Intent): Pair<String, String>? {
        val uri = intent.data ?: return null
        val link = VaultDeepLinkParser.parseBeneficiaryAccept(uri) ?: return null
        return link.vaultId to link.token
    }

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
    beneficiaryAccept: Pair<String, String>?,
    vaultDeepLink: VaultDeepLink?,
    onBeneficiaryAcceptConsumed: () -> Unit,
    onVaultDeepLinkConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity

    fun promptUnlock() {
        BiometricHelper(activity).authenticate(
            title = "Unlock Ethos-Protocol",
            subtitle = "Confirm it's you to continue",
            onSuccess = { authVm.unlock() },
            onError = { /* stays locked — the overlay's button lets the user retry */ }
        )
    }

    LaunchedEffect(authState.isLocked) {
        if (authState.isLocked) promptUnlock()
    }

    LaunchedEffect(authState.isAuthenticated) {
        if (authState.isAuthenticated) navController.navigate("vaults") { popUpTo("auth") { inclusive = true } }
        else navController.navigate("auth") { popUpTo("vaults") { inclusive = true } }
    }

    LaunchedEffect(beneficiaryAccept, authState.isAuthenticated) {
        if (beneficiaryAccept != null && authState.isAuthenticated) {
            val (vaultId, token) = beneficiaryAccept
            navController.navigate("accept/$vaultId/$token")
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

    Box(Modifier.fillMaxSize()) {
        NavHost(navController, startDestination = if (authState.isAuthenticated) "vaults" else "auth") {
            composable("auth") { AuthScreen(vm = authVm) }
            composable("vaults") {
                VaultListScreen(onVaultClick = { /* navigate to detail */ })
            }
            composable("accept/{vaultId}/{token}") { backStack ->
                val vaultId = backStack.arguments?.getString("vaultId") ?: return@composable
                val token = backStack.arguments?.getString("token") ?: return@composable
                BeneficiaryAcceptanceScreen(
                    vaultId = vaultId,
                    token = token,
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
}
