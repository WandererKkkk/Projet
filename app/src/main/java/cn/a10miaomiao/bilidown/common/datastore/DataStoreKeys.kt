package cn.a10miaomiao.bilidown.common.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object DataStoreKeys {

    val appPackageNameSet = stringSetPreferencesKey("app_package_name_set")

    val enabledShizuku = booleanPreferencesKey("enabled_shizuku")

    val mmsoFolderUri = stringPreferencesKey("mmso_folder_uri")

    val mmsoDbUri = stringPreferencesKey("mmso_db_uri")
}