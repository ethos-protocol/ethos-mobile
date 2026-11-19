package com.ethosprotocol.ui

import androidx.lifecycle.ViewModel
import com.ethosprotocol.services.NotificationDeliveryLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Backs the debug-only notification log screen (#235, #728). It exposes the local
 * [NotificationDeliveryLog] so the screen can list and clear recent delivery events.
 */
@HiltViewModel
class NotificationDebugViewModel @Inject constructor(
    val deliveryLog: NotificationDeliveryLog,
) : ViewModel()
