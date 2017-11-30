package nl.adaptivity.android.darwin

import android.accounts.Account
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AccountChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action==AccountManager.ACTION_ACCOUNT_REMOVED) {
            val storedAccount = AuthenticatedWebClientFactory.getStoredAccount(context)
            if (storedAccount?.name == intent.accountName && storedAccount?.type == intent.accountType) {
                AuthenticatedWebClientFactory.setStoredAccount(context, null)
            }
        }
    }
}

internal inline val Intent.accountName: String? get() = extras.getString(AccountManager.KEY_ACCOUNT_NAME)
internal inline val Intent.accountType: String? get() = extras.getString(AccountManager.KEY_ACCOUNT_TYPE)
internal inline val Intent.account: Account? get() {
    return Account(accountName ?: return null, accountType ?: return null)
}