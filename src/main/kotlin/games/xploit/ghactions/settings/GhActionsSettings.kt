package games.xploit.ghactions.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "GhActionsSettings", storages = [Storage("ghActionsPlugin.xml")])
class GhActionsSettings : SimplePersistentStateComponent<GhActionsSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var refreshSeconds by property(15)
        var runsPerPage by property(30)
        var currentBranchOnly by property(false)
        var showTimestamps by property(false)
    }

    companion object {
        fun getInstance(): GhActionsSettings = service()

        private val credentials = CredentialAttributes(generateServiceName("GH Actions plugin", "token"))

        /** Token stored in the IDE password safe. Slow, avoid calling on EDT in hot paths. */
        var token: String?
            get() = PasswordSafe.instance.getPassword(credentials)
            set(value) = PasswordSafe.instance.setPassword(credentials, value?.trim()?.takeIf { it.isNotEmpty() })
    }
}
