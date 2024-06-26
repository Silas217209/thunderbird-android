package com.fsck.k9.preferences

import android.content.Context
import com.fsck.k9.Account
import com.fsck.k9.AccountPreferenceSerializer
import com.fsck.k9.Core
import com.fsck.k9.K9
import com.fsck.k9.Preferences
import com.fsck.k9.ServerSettingsSerializer
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mailstore.SpecialLocalFoldersCreator
import com.fsck.k9.preferences.ServerTypeConverter.toServerSettingsType
import com.fsck.k9.preferences.Settings.InvalidSettingValueException
import java.io.InputStream
import java.util.UUID
import kotlinx.datetime.Clock
import timber.log.Timber

// TODO: Further refactor this class to be able to get rid of these detekt issues.
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "SwallowedException",
    "ReturnCount",
    "ThrowsCount",
)
class SettingsImporter internal constructor(
    private val settingsFileParser: SettingsFileParser,
    private val preferences: Preferences,
    private val generalSettingsManager: RealGeneralSettingsManager,
    private val localFoldersCreator: SpecialLocalFoldersCreator,
    private val serverSettingsSerializer: ServerSettingsSerializer,
    private val clock: Clock,
    private val context: Context,
) {
    /**
     * Parses an import [InputStream] and returns information on whether it contains global settings and/or account
     * settings. For all account configurations found, the name of the account along with the account UUID is returned.
     *
     * @param inputStream An `InputStream` to read the settings from.
     *
     * @return An [ImportContents] instance containing information about the contents of the settings file.
     *
     * @throws SettingsImportExportException In case of an error.
     */
    @Throws(SettingsImportExportException::class)
    fun getImportStreamContents(inputStream: InputStream): ImportContents {
        try {
            // Parse the import stream but don't save individual settings (overview=true)
            val imported = settingsFileParser.parseSettings(
                inputStream = inputStream,
                globalSettings = false,
                accountUuids = null,
                overview = true,
            )

            // If the stream contains global settings the "globalSettings" member will not be null
            val globalSettings = (imported.globalSettings != null)

            val accounts = imported.accounts?.values.orEmpty().map { importedAccount ->
                AccountDescription(
                    name = getAccountDisplayName(importedAccount),
                    uuid = importedAccount.uuid,
                )
            }

            // TODO: throw exception if neither global settings nor account settings could be found
            return ImportContents(globalSettings, accounts)
        } catch (e: SettingsImportExportException) {
            throw e
        } catch (e: Exception) {
            throw SettingsImportExportException(e)
        }
    }

    /**
     * Reads an import [InputStream] and imports the global settings and/or account configurations specified by the
     * arguments.
     *
     * @param inputStream The `InputStream` to read the settings from.
     * @param globalSettings `true` if global settings should be imported from the file.
     * @param accountUuids A list of UUIDs of the accounts that should be imported.
     *
     * @return An [ImportResults] instance containing information about errors and successfully imported accounts.
     *
     * @throws SettingsImportExportException In case of an error.
     */
    @Throws(SettingsImportExportException::class)
    fun importSettings(
        inputStream: InputStream,
        globalSettings: Boolean,
        accountUuids: List<String>?,
    ): ImportResults {
        try {
            var globalSettingsImported = false
            val importedAccounts = mutableListOf<AccountDescriptionPair>()
            val erroneousAccounts = mutableListOf<AccountDescription>()

            val imported = settingsFileParser.parseSettings(
                inputStream = inputStream,
                globalSettings = globalSettings,
                accountUuids = accountUuids,
                overview = false,
            )

            val storage = preferences.storage

            if (globalSettings) {
                try {
                    val editor = preferences.createStorageEditor()
                    if (imported.globalSettings != null) {
                        importGlobalSettings(storage, editor, imported.contentVersion, imported.globalSettings)
                    } else {
                        Timber.w("Was asked to import global settings but none found.")
                    }

                    if (editor.commit()) {
                        Timber.v("Committed global settings to the preference storage.")
                        globalSettingsImported = true
                    } else {
                        Timber.v("Failed to commit global settings to the preference storage")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception while importing global settings")
                }
            }

            if (!accountUuids.isNullOrEmpty()) {
                if (imported.accounts != null) {
                    for (accountUuid in accountUuids) {
                        val account = imported.accounts[accountUuid]
                        if (account != null) {
                            try {
                                var editor = preferences.createStorageEditor()

                                val importResult = importAccount(editor, imported.contentVersion, account)

                                if (editor.commit()) {
                                    Timber.v(
                                        "Committed settings for account \"%s\" to the settings database.",
                                        importResult.imported.name,
                                    )

                                    // Add UUID of the account we just imported to the list of account UUIDs
                                    editor = preferences.createStorageEditor()

                                    val newUuid = importResult.imported.uuid
                                    val oldAccountUuids = preferences.storage.getString("accountUuids", "")
                                    val newAccountUuids = if (oldAccountUuids.isNotEmpty()) {
                                        "$oldAccountUuids,$newUuid"
                                    } else {
                                        newUuid
                                    }

                                    putString(editor, "accountUuids", newAccountUuids)

                                    if (!editor.commit()) {
                                        throw SettingsImportExportException("Failed to set account UUID list")
                                    }

                                    // Reload accounts
                                    preferences.loadAccounts()

                                    importedAccounts.add(importResult)
                                } else {
                                    Timber.w(
                                        "Error while committing settings for account \"%s\" to the settings database.",
                                        importResult.original.name,
                                    )

                                    erroneousAccounts.add(importResult.original)
                                }
                            } catch (e: InvalidSettingValueException) {
                                Timber.e(e, "Encountered invalid setting while importing account \"%s\"", account.name)

                                erroneousAccounts.add(AccountDescription(account.name!!, account.uuid))
                            } catch (e: Exception) {
                                Timber.e(e, "Exception while importing account \"%s\"", account.name)

                                erroneousAccounts.add(AccountDescription(account.name!!, account.uuid))
                            }
                        } else {
                            Timber.w(
                                "Was asked to import account with UUID %s. But this account wasn't found.",
                                accountUuid,
                            )
                        }
                    }

                    val editor = preferences.createStorageEditor()

                    if (!editor.commit()) {
                        throw SettingsImportExportException("Failed to set default account")
                    }
                } else {
                    Timber.w("Was asked to import at least one account but none found.")
                }
            }

            preferences.loadAccounts()

            // Create special local folders
            for (importedAccount in importedAccounts) {
                val accountUuid = importedAccount.imported.uuid
                val account = preferences.getAccount(accountUuid) ?: error("Failed to load account: $accountUuid")

                localFoldersCreator.createSpecialLocalFolders(account)
            }

            generalSettingsManager.loadSettings()
            Core.setServicesEnabled(context)

            return ImportResults(globalSettingsImported, importedAccounts, erroneousAccounts)
        } catch (e: SettingsImportExportException) {
            throw e
        } catch (e: Exception) {
            throw SettingsImportExportException(e)
        }
    }

    private fun importGlobalSettings(
        storage: Storage,
        editor: StorageEditor,
        contentVersion: Int,
        settings: ImportedSettings,
    ) {
        // Validate global settings
        val validatedSettings = GeneralSettingsDescriptions.validate(contentVersion, settings.settings)

        // Upgrade global settings to current content version
        if (contentVersion != Settings.VERSION) {
            GeneralSettingsDescriptions.upgrade(contentVersion, validatedSettings)
        }

        // Convert global settings to the string representation used in preference storage
        val stringSettings = GeneralSettingsDescriptions.convert(validatedSettings)

        // Use current global settings as base and overwrite with validated settings read from the import file.
        val mergedSettings = GeneralSettingsDescriptions.getGlobalSettings(storage).toMutableMap()
        mergedSettings.putAll(stringSettings)

        for ((key, value) in mergedSettings) {
            putString(editor, key, value)
        }
    }

    @Throws(InvalidSettingValueException::class)
    private fun importAccount(
        editor: StorageEditor,
        contentVersion: Int,
        account: ImportedAccount,
    ): AccountDescriptionPair {
        val original = AccountDescription(account.name!!, account.uuid)

        val prefs = Preferences.getPreferences()
        val accounts = prefs.getAccounts()

        val existingAccount = prefs.getAccount(account.uuid)
        val uuid = if (existingAccount != null) {
            // An account with this UUID already exists. So generate a new UUID.
            UUID.randomUUID().toString()
        } else {
            account.uuid
        }

        // Make sure the account name is unique
        var accountName = account.name
        if (isAccountNameUsed(accountName, accounts)) {
            // Account name is already in use. So generate a new one by appending " (x)", where x is the first
            // number >= 1 that results in an unused account name.
            for (i in 1..accounts.size) {
                accountName = account.name + " (" + i + ")"
                if (!isAccountNameUsed(accountName, accounts)) {
                    break
                }
            }
        }

        // Write account name
        val accountKeyPrefix = "$uuid."
        putString(editor, accountKeyPrefix + AccountPreferenceSerializer.ACCOUNT_DESCRIPTION_KEY, accountName)

        if (account.incoming == null) {
            // We don't import accounts without incoming server settings
            throw InvalidSettingValueException("Missing incoming server settings")
        }

        // Write incoming server settings
        val incoming = createServerSettings(account.incoming)
        val incomingServer = serverSettingsSerializer.serialize(incoming)
        putString(editor, accountKeyPrefix + AccountPreferenceSerializer.INCOMING_SERVER_SETTINGS_KEY, incomingServer)

        val incomingServerName = incoming.host
        val incomingPasswordNeeded =
            incoming.authenticationType != AuthType.EXTERNAL && incoming.authenticationType != AuthType.XOAUTH2 &&
                incoming.password.isNullOrEmpty()

        var authorizationNeeded = incoming.authenticationType == AuthType.XOAUTH2

        if (account.outgoing == null) {
            throw InvalidSettingValueException("Missing outgoing server settings")
        }

        // Write outgoing server settings
        val outgoing = createServerSettings(account.outgoing)
        val outgoingServer = serverSettingsSerializer.serialize(outgoing)
        putString(editor, accountKeyPrefix + AccountPreferenceSerializer.OUTGOING_SERVER_SETTINGS_KEY, outgoingServer)

        /*
         * Mark account as disabled if the settings file contained a username but no password, except when the
         * AuthType is EXTERNAL.
         */
        val outgoingPasswordNeeded =
            outgoing.authenticationType != AuthType.EXTERNAL && outgoing.authenticationType != AuthType.XOAUTH2 &&
                outgoing.username.isNotEmpty() && outgoing.password.isNullOrEmpty()

        authorizationNeeded = authorizationNeeded || outgoing.authenticationType == AuthType.XOAUTH2

        val outgoingServerName = outgoing.host

        val createAccountDisabled = incomingPasswordNeeded || outgoingPasswordNeeded || authorizationNeeded
        if (createAccountDisabled) {
            editor.putBoolean(accountKeyPrefix + "enabled", false)
        }

        // Validate account settings
        val validatedSettings =
            AccountSettingsDescriptions.validate(contentVersion, account.settings!!.settings, true)

        // Upgrade account settings to current content version
        if (contentVersion != Settings.VERSION) {
            AccountSettingsDescriptions.upgrade(contentVersion, validatedSettings)
        }

        // Convert account settings to the string representation used in preference storage
        val stringSettings = AccountSettingsDescriptions.convert(validatedSettings)

        // Write account settings
        for ((accountKey, value) in stringSettings) {
            val key = accountKeyPrefix + accountKey
            putString(editor, key, value)
        }

        // Generate and write a new "accountNumber"
        val newAccountNumber = prefs.generateAccountNumber()
        putString(editor, accountKeyPrefix + "accountNumber", newAccountNumber.toString())

        // Write identities
        if (account.identities != null) {
            importIdentities(editor, contentVersion, uuid, account)
        } else {
            // Require accounts to at least have one identity
            throw InvalidSettingValueException("Missing identities, there should be at least one.")
        }

        // Write folder settings
        if (account.folders != null) {
            for (folder in account.folders) {
                importFolder(editor, contentVersion, uuid, folder)
            }
        }

        // When deleting an account and then restoring it using settings import, the same account UUID will be used.
        // To avoid reusing a previously existing notification channel ID, we need to make sure to use a unique value
        // for `messagesNotificationChannelVersion`.
        val messageNotificationChannelVersion = clock.now().epochSeconds.toString()
        putString(editor, accountKeyPrefix + "messagesNotificationChannelVersion", messageNotificationChannelVersion)

        val imported = AccountDescription(accountName!!, uuid)
        return AccountDescriptionPair(
            original,
            imported,
            authorizationNeeded,
            incomingPasswordNeeded,
            outgoingPasswordNeeded,
            incomingServerName!!,
            outgoingServerName!!,
        )
    }

    private fun importFolder(
        editor: StorageEditor,
        contentVersion: Int,
        uuid: String,
        folder: ImportedFolder,
    ) {
        // Validate folder settings
        val validatedSettings =
            FolderSettingsDescriptions.validate(contentVersion, folder.settings!!.settings, true)

        // Upgrade folder settings to current content version
        if (contentVersion != Settings.VERSION) {
            FolderSettingsDescriptions.upgrade(contentVersion, validatedSettings)
        }

        // Convert folder settings to the string representation used in preference storage
        val stringSettings = FolderSettingsDescriptions.convert(validatedSettings)

        // Write folder settings
        val prefix = uuid + "." + folder.name + "."
        for ((folderKey, value) in stringSettings) {
            val key = prefix + folderKey
            putString(editor, key, value)
        }
    }

    @Throws(InvalidSettingValueException::class)
    private fun importIdentities(
        editor: StorageEditor,
        contentVersion: Int,
        uuid: String,
        account: ImportedAccount,
    ) {
        val accountKeyPrefix = "$uuid."

        // Gather information about existing identities for this account (if any)

        // Write identities
        for ((index, identity) in account.identities!!.withIndex()) {
            val identitySuffix = ".$index"

            // Write name used in identity
            val identityName = identity.name.orEmpty()
            putString(
                editor,
                accountKeyPrefix + AccountPreferenceSerializer.IDENTITY_NAME_KEY + identitySuffix,
                identityName,
            )

            // Validate email address
            if (!IdentitySettingsDescriptions.isEmailAddressValid(identity.email)) {
                throw InvalidSettingValueException("Invalid email address: " + identity.email)
            }

            // Write email address
            putString(
                editor,
                accountKeyPrefix + AccountPreferenceSerializer.IDENTITY_EMAIL_KEY + identitySuffix,
                identity.email,
            )

            // Write identity description
            if (identity.description != null) {
                putString(
                    editor,
                    accountKeyPrefix + AccountPreferenceSerializer.IDENTITY_DESCRIPTION_KEY + identitySuffix,
                    identity.description,
                )
            }

            if (identity.settings != null) {
                // Validate identity settings
                val validatedSettings = IdentitySettingsDescriptions.validate(
                    contentVersion,
                    identity.settings.settings,
                    true,
                )

                // Upgrade identity settings to current content version
                if (contentVersion != Settings.VERSION) {
                    IdentitySettingsDescriptions.upgrade(contentVersion, validatedSettings)
                }

                // Convert identity settings to the representation used in preference storage
                val stringSettings = IdentitySettingsDescriptions.convert(validatedSettings)

                // Write identity settings
                for ((identityKey, value) in stringSettings) {
                    val key = accountKeyPrefix + identityKey + identitySuffix
                    putString(editor, key, value)
                }
            }
        }
    }

    private fun isAccountNameUsed(name: String?, accounts: List<Account>): Boolean {
        return accounts.any { it.displayName == name }
    }

    /**
     * Write to a [StorageEditor] while logging what is written if debug logging is enabled.
     *
     * @param editor The `Editor` to write to.
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     */
    private fun putString(editor: StorageEditor, key: String, value: String?) {
        if (K9.isDebugLoggingEnabled) {
            var outputValue = value
            if (!K9.isSensitiveDebugLoggingEnabled &&
                (
                    key.endsWith("." + AccountPreferenceSerializer.OUTGOING_SERVER_SETTINGS_KEY) ||
                        key.endsWith("." + AccountPreferenceSerializer.INCOMING_SERVER_SETTINGS_KEY)
                    )
            ) {
                outputValue = "*sensitive*"
            }

            Timber.v("Setting %s=%s", key, outputValue)
        }

        editor.putString(key, value)
    }

    private fun getAccountDisplayName(account: ImportedAccount): String {
        return account.name?.takeIf { it.isNotEmpty() }
            ?: account.identities?.firstOrNull()?.email
            ?: error("Account name missing")
    }

    private fun createServerSettings(importedServer: ImportedServer): ServerSettings {
        val type = toServerSettingsType(importedServer.type!!)
        val port = convertPort(importedServer.port)
        val connectionSecurity = convertConnectionSecurity(importedServer.connectionSecurity)
        val password = if (importedServer.authenticationType == AuthType.XOAUTH2) "" else importedServer.password
        val extra = importedServer.extras?.settings.orEmpty()

        return ServerSettings(
            type,
            importedServer.host,
            port,
            connectionSecurity,
            importedServer.authenticationType!!,
            importedServer.username!!,
            password,
            importedServer.clientCertificateAlias,
            extra,
        )
    }

    private fun convertPort(port: String?): Int {
        return port?.toIntOrNull() ?: -1
    }

    private fun convertConnectionSecurity(connectionSecurity: String?): ConnectionSecurity {
        try {
            // TODO: Add proper settings validation and upgrade capability for server settings. Once that exists, move
            //  this code into a SettingsUpgrader.
            if ("SSL_TLS_OPTIONAL" == connectionSecurity) {
                return ConnectionSecurity.SSL_TLS_REQUIRED
            } else if ("STARTTLS_OPTIONAL" == connectionSecurity) {
                return ConnectionSecurity.STARTTLS_REQUIRED
            }
            return ConnectionSecurity.valueOf(connectionSecurity!!)
        } catch (e: Exception) {
            return ConnectionSecurity.SSL_TLS_REQUIRED
        }
    }
}
