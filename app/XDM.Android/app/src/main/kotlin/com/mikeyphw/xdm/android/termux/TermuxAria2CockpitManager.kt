package com.mikeyphw.xdm.android.termux

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class TermuxAria2CockpitManager(context: Context) {
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    private val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val statusFlow = MutableStateFlow(loadStatus())

    val status: StateFlow<TermuxAria2CockpitStatus> = statusFlow

    fun refreshStatus() {
        runner.refreshStatus()
        statusFlow.update { current ->
            val enabled = preferences.getBoolean(EnabledKey, false)
            current.copy(
                enabled = enabled,
                daemonState = if (!enabled) TermuxAria2DaemonState.Disabled else current.daemonState.coerceEnabled(),
                config = if (enabled) config() else current.config,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(EnabledKey, enabled).apply()
        statusFlow.update {
            it.copy(
                enabled = enabled,
                daemonState = if (enabled) TermuxAria2DaemonState.Stopped else TermuxAria2DaemonState.Disabled,
                config = if (enabled) config() else it.config,
                lastAction = if (enabled) "Termux aria2 backend enabled." else "Termux aria2 backend disabled.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun rotateSecret() {
        preferences.edit().putString(SecretKey, generateSecret()).apply()
        statusFlow.update {
            it.copy(
                config = config(),
                daemonState = if (it.enabled) TermuxAria2DaemonState.Stopped else TermuxAria2DaemonState.Disabled,
                lastAction = "RPC secret rotated. Restart the Termux aria2 daemon.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun startDaemon() = launch(
        command = XdmTermuxCommand.Aria2StartDaemon(config()),
        optimisticState = TermuxAria2DaemonState.Running,
        successMessage = "Start command sent to Termux aria2 RPC daemon.",
    )

    fun stopDaemon() = launch(
        command = XdmTermuxCommand.Aria2StopDaemon(config()),
        optimisticState = TermuxAria2DaemonState.Stopped,
        successMessage = "Stop command sent to Termux aria2 RPC daemon.",
    )

    fun probeDaemon() = launch(
        command = XdmTermuxCommand.Aria2ProbeDaemon(config()),
        optimisticState = if (statusFlow.value.daemonState == TermuxAria2DaemonState.Disabled) TermuxAria2DaemonState.Stopped else statusFlow.value.daemonState,
        successMessage = "Checking Termux aria2 RPC daemon.",
    )

    fun saveSession() = launch(
        command = XdmTermuxCommand.Aria2SaveSession(config()),
        optimisticState = statusFlow.value.daemonState,
        successMessage = "Saving Termux aria2 session.",
    )

    fun refreshTasks() = launch(
        command = XdmTermuxCommand.Aria2TellActive(config()),
        optimisticState = statusFlow.value.daemonState,
        successMessage = "Refreshing Termux aria2 active tasks.",
    )

    fun pauseAll() = launch(
        command = XdmTermuxCommand.Aria2PauseAll(config()),
        optimisticState = statusFlow.value.daemonState,
        successMessage = "Pausing Termux aria2 tasks.",
    )

    fun resumeAll() = launch(
        command = XdmTermuxCommand.Aria2ResumeAll(config()),
        optimisticState = statusFlow.value.daemonState,
        successMessage = "Resuming Termux aria2 tasks.",
    )

    private fun launch(command: XdmTermuxCommand, optimisticState: TermuxAria2DaemonState, successMessage: String) {
        val enabled = preferences.getBoolean(EnabledKey, false)
        if (!enabled) {
            statusFlow.update {
                it.copy(
                    enabled = false,
                    daemonState = TermuxAria2DaemonState.Disabled,
                    lastAction = "Enable Termux aria2 before running cockpit actions.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            return
        }
        val result = runner.run(command)
        val now = System.currentTimeMillis()
        if (result.started) {
            statusFlow.update {
                it.copy(
                    enabled = true,
                    daemonState = optimisticState,
                    config = config(),
                    lastAction = successMessage,
                    lastHealth = if (command is XdmTermuxCommand.Aria2ProbeDaemon || command is XdmTermuxCommand.Aria2TellActive) "RPC probe queued; check recent Termux runs for the raw response." else it.lastHealth,
                    updatedAtEpochMs = now,
                )
            }
        } else {
            TermuxRunStore.recordLaunchFailure(appContext, command.operation, result.error)
            statusFlow.update {
                it.copy(
                    enabled = true,
                    daemonState = TermuxAria2DaemonState.Failed,
                    config = config(),
                    lastAction = result.error,
                    lastHealth = result.error,
                    updatedAtEpochMs = now,
                )
            }
        }
    }

    private fun loadStatus(): TermuxAria2CockpitStatus {
        val enabled = preferences.getBoolean(EnabledKey, false)
        return TermuxAria2CockpitStatus(
            enabled = enabled,
            daemonState = if (enabled) TermuxAria2DaemonState.Stopped else TermuxAria2DaemonState.Disabled,
            config = if (enabled) config() else null,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun config(): TermuxAria2RpcConfig {
        val home = TermuxPaths.home(appContext)
        val stateDir = "$home/.local/share/xdm/aria2"
        val port = preferences.getInt(PortKey, DefaultPort).coerceIn(1024, 65535)
        val secret = preferences.getString(SecretKey, null)?.takeIf { it.length >= 24 } ?: generateSecret().also { generated ->
            preferences.edit().putString(SecretKey, generated).putInt(PortKey, port).apply()
        }
        return TermuxAria2RpcConfig(
            port = port,
            secret = secret,
            downloadDir = "$home/storage/downloads/XDM",
            sessionFile = "$stateDir/aria2.session",
            logFile = "$stateDir/aria2.log",
        )
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun TermuxAria2DaemonState.coerceEnabled(): TermuxAria2DaemonState = if (this == TermuxAria2DaemonState.Disabled) TermuxAria2DaemonState.Stopped else this

    private companion object {
        const val PreferencesName = "xdm_termux_aria2_cockpit"
        const val EnabledKey = "enabled"
        const val SecretKey = "rpc_secret"
        const val PortKey = "rpc_port"
        const val DefaultPort = 16800
    }
}
