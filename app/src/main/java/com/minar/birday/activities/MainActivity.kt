package com.minar.birday.activities

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioAttributes.Builder
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.snackbar.Snackbar
import com.minar.birday.R
import com.minar.birday.databinding.ActivityMainBinding
import com.minar.birday.fragments.dialogs.ImportContactsBottomSheet
import com.minar.birday.fragments.dialogs.InsertEventBottomSheet
import com.minar.birday.preferences.backup.BirdayImporter
import com.minar.birday.preferences.backup.ContactsImporter
import com.minar.birday.preferences.backup.CsvImporter
import com.minar.birday.preferences.backup.JsonImporter
import com.minar.birday.utilities.*
import com.minar.birday.viewmodels.MainViewModel
import com.minar.birday.widgets.EventWidgetProvider
import com.minar.birday.widgets.MinimalWidgetProvider
import java.io.IOException
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding

    private val navController: NavController
        get() {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.navHostFragment) as NavHostFragment
            return navHostFragment.navController
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Create the notification channel and check the permission on Tiramisu
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Contacts permission is asked after the response to this permission on tiramisu
            if (askNotificationPermission())
            // Ask for contacts permission, if the first permission is already granted
                askContactsPermission()
        } else {
            askContactsPermission()
        }
        createNotificationChannel()

        // Register back pressed callback (do not use onBackPressed() - deprecated)
        onBackPressedDispatcher.addCallback(this) {
            if (navController.currentDestination?.id == R.id.navigationMain) {
                finish()
            } else {
                binding.navigation.selectedItemId = R.id.navigationMain
                navController.navigateWithOptions(R.id.navigationMain)
            }
        }

        // Retrieve the shared preferences
        val theme = sharedPrefs.getString("theme_color", "system")
        val accent = sharedPrefs.getString("accent_color", "system")

        // Show the introduction for the first launch
        if (sharedPrefs.getBoolean("first", true)) {
            val editor = sharedPrefs.edit()
            editor.putBoolean("first", false)
            // Set default accent based on the Android version
            when (Build.VERSION.SDK_INT) {
                in 23..29 -> editor.putString("accent_color", "blue")
                31 -> editor.putString("accent_color", "system")
                else -> editor.putString("accent_color", "monet")
            }
            editor.apply()
            val intent = Intent(this, WelcomeActivity::class.java)
            startActivity(intent)
            finish()
        }

        // Set the base theme and the accent
        when (theme) {
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        when (accent) {
            "monet" -> setTheme(R.style.AppTheme_Monet)
            "system" -> setTheme(R.style.AppTheme_System)
            "brown" -> setTheme(R.style.AppTheme_Brown)
            "blue" -> setTheme(R.style.AppTheme_Blue)
            "green" -> setTheme(R.style.AppTheme_Green)
            "orange" -> setTheme(R.style.AppTheme_Orange)
            "yellow" -> setTheme(R.style.AppTheme_Yellow)
            "teal" -> setTheme(R.style.AppTheme_Teal)
            "violet" -> setTheme(R.style.AppTheme_Violet)
            "pink" -> setTheme(R.style.AppTheme_Pink)
            "lightBlue" -> setTheme(R.style.AppTheme_LightBlue)
            "red" -> setTheme(R.style.AppTheme_Red)
            "lime" -> setTheme(R.style.AppTheme_Lime)
            "crimson" -> setTheme(R.style.AppTheme_Crimson)
            else -> setTheme(R.style.AppTheme) // Default (aqua)
        }

        // Set the task appearance in recent apps
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setTaskDescription(
                ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    R.mipmap.ic_launcher,
                    ContextCompat.getColor(this, R.color.deepGray)
                )
            )
        } else setTaskDescription(
            ActivityManager.TaskDescription(
                getString(R.string.app_name),
                ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap(),
                ContextCompat.getColor(this, R.color.deepGray)
            )
        )

        // Initialize the binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // Get the bottom navigation bar and configure it for the navigation plugin
        val navigation = binding.navigation

        navigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigationMain ->
                    navController.navigateWithOptions(R.id.navigationMain)
                R.id.navigationFavorites ->
                    navController.navigateWithOptions(R.id.navigationFavorites)
                R.id.navigationSettings ->
                    navController.navigateWithOptions(R.id.navigationSettings)
            }
            true
        }
        navigation.setOnItemReselectedListener {
            // Only do something if there's something in the back stack (only in event details)
            if (navController.currentBackStackEntry != null &&
                (navController.currentDestination?.label == "fragment_details" ||
                        navController.currentDestination?.label == "fragment_overview" ||
                        navController.currentDestination?.label == "fragment_experimental_settings")
            )
                navController.popBackStack()
        }

        // Rating stuff
        AppRater.appLaunched(this)

        // Manage the fab
        val addFab = binding.fab
        val deleteFab = binding.fabDelete

        // Open the bottom sheet to insert a new event
        addFab.setOnClickListener {
            vibrate()
            val bottomSheet = InsertEventBottomSheet(this)
            if (bottomSheet.isAdded) return@setOnClickListener
            bottomSheet.show(supportFragmentManager, "insert_event_bottom_sheet")
        }
        // Show a quick description of the action
        addFab.setOnLongClickListener {
            vibrate()
            showSnackbar(getString(R.string.new_event_description))
            true
        }

        // Animate the fab icon
        addFab.applyLoopingAnimatedVectorDrawable(R.drawable.animated_add_event, 5000L)

        // Set the delete search action (initially hidden)
        deleteFab.setOnClickListener {
            vibrate()
            val searchedEvents = mainViewModel.allEvents.value
            if (searchedEvents != null && searchedEvents.isNotEmpty()) {
                // Native dialog
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.delete_db_dialog_title))
                    .setMessage(getString(R.string.delete_search_confirm))
                    .setIcon(R.drawable.ic_delete_24dp)
                    .setPositiveButton(resources.getString(android.R.string.ok)) { dialog, _ ->
                        dialog.dismiss()
                        mainViewModel.deleteAll(searchedEvents.map { resultToEvent(it) })
                        showSnackbar(
                            getString(R.string.deleted),
                            actionText = getString(R.string.cancel),
                            action = fun() {
                                mainViewModel.insertAll(searchedEvents.map { resultToEvent(it) })
                            })
                    }
                    .setNegativeButton(resources.getString(android.R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        // Show a quick description of the action
        deleteFab.setOnLongClickListener {
            vibrate()
            showSnackbar(getString(R.string.delete_search_title))
            true
        }

        // Navigation bar color management (if executed before, it doesn't work)
        if (accent == "monet") {
            DynamicColors.applyToActivityIfAvailable(this)
            window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        }

        // Auto import on launch TODO Only available in experimental settings
        if (sharedPrefs.getBoolean("auto_import", false)) {
            val currentLaunchTime = System.currentTimeMillis()
            val lastLaunch = sharedPrefs.getLong("last_launch", 0L)

            // Only launch the auto import if 3 minutes are passed
            if (lastLaunch + (3 * 60 * 1000) < currentLaunchTime) {
                sharedPrefs.edit().putLong("last_launch", currentLaunchTime).apply()
                thread {
                    ContactsImporter(this, null).importContacts(this)
                }
            }
        }

        // Hide on scroll, requires restart TODO Only available in experimental settings
        if (sharedPrefs.getBoolean("hide_scroll", false)) {
            binding.bottomBar.hideOnScroll = true
            binding.navHostFragment.setPadding(0, 0, 0, 0)
        }

        // Only the next events, without considering the search string, ordered
        mainViewModel.allEventsUnfiltered.observe(this)
        {
            // Update the widgets using this livedata, to avoid strange behaviors when searching
            updateWidget()
        }
    }

    private fun NavController.navigateWithOptions(@IdRes destination: Int) {
        // Only way to use custom animations with the bottom navigation bar
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setEnterAnim(R.anim.nav_enter_anim)
            .setExitAnim(R.anim.nav_exit_anim)
            .setPopEnterAnim(R.anim.nav_pop_enter_anim)
            .setPopExitAnim(R.anim.nav_pop_exit_anim)
            .setPopUpTo(R.id.nav_graph, true)
            .build()

        navigate(destination, null, options)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Manage refresh from settings, since there's a bug where the refresh doesn't work properly
        val refreshed = sharedPrefs.getBoolean("refreshed", false)
        if (refreshed) {
            sharedPrefs.edit().putBoolean("refreshed", false).apply()
            super.onSaveInstanceState(outState)
        } else {
            // Dirty, dirty fix to avoid TransactionTooBigException:
            // it will restore the home fragment when the theme is changed from system for example,
            // and the app is in recent apps. No issues for screen rotations, keyboard and so on
            super.onSaveInstanceState(Bundle())
        }
    }

    // Update the existing widgets with the newest data and the onclick action
    private fun updateWidget() {
        val intentUpcoming = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        intentUpcoming.component = ComponentName(this, EventWidgetProvider::class.java)
        sendBroadcast(intentUpcoming)

        val intentMinimal = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        intentMinimal.component = ComponentName(this, MinimalWidgetProvider::class.java)
        sendBroadcast(intentMinimal)
    }

    // Create the NotificationChannel. This code does nothing when it already exists
    private fun createNotificationChannel() {
        val soundUri =
            Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + applicationContext.packageName + "/" + R.raw.birday_notification)
        val attributes: AudioAttributes = Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val name = getString(R.string.events_notification_channel)
        val descriptionText = getString(R.string.events_channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("events_channel", name, importance).apply {
            description = descriptionText
        }
        // Additional tuning over sound, vibration and notification light
        channel.setSound(soundUri, attributes)
        channel.enableLights(true)
        channel.lightColor = Color.GREEN
        channel.enableVibration(true)
        // Register the channel with the system
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // Choose a backup registering a callback and following the latest guidelines
    val selectBackup =
        registerForActivityResult(ActivityResultContracts.GetContent()) { fileUri: Uri? ->
            try {
                if (fileUri == null) return@registerForActivityResult
                // Select the correct importer. Always use native import, except for JSON and csv
                when (getFileName(fileUri).split(".").last()) {
                    "json" -> {
                        val jsonImporter = JsonImporter(this, null)
                        jsonImporter.importEventsJson(this, fileUri)
                    }
                    "csv" -> {
                        val csvImporter = CsvImporter(this, null)
                        csvImporter.importEventsCsv(this, fileUri)
                    }
                    else -> {
                        val birdayImporter = BirdayImporter(this, null)
                        birdayImporter.importEvents(this, fileUri)
                    }
                }
            } catch (e: IOException) {
                // Invalid file, other errors, can't even try to import
                e.printStackTrace()
                showSnackbar(getString(R.string.birday_import_failure))
            }
        }


    // Some utility functions, used from every fragment connected to this activity

    // Given an uri, find the file name
    private fun getFileName(uri: Uri): String {
        var result = ""
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor.use {
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex == -1) return@use
                    result = cursor.getString(columnIndex)
                }
            }
        }
        return result
    }

    // Vibrate using a standard vibration pattern
    // or use system Haptic feedback if vibration is disabled
    fun vibrate() {
        // Deprecated for no reason
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                this.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (sharedPrefs.getBoolean("vibration", false)) {
            // Vibrate if the vibration in options is set to on
            vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use system Haptic feedback
            if (vib.areEffectsSupported(VibrationEffect.EFFECT_CLICK)[0] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES)
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    // Show a snackbar containing a given text and an optional action, with a 5 seconds duration
    fun showSnackbar(
        content: String,
        attachView: View? = null,
        action: (() -> Unit)? = null,
        actionText: String? = null,
    ) {
        val snackbar = Snackbar.make(binding.root, content, 5000)
        snackbar.isGestureInsetBottomIgnored = true
        if (attachView != null)
            snackbar.anchorView = attachView
        else
            snackbar.anchorView = binding.bottomBar
        if (action != null) {
            snackbar.setActionTextColor(getThemeColor(android.R.attr.colorSecondary, this))
            snackbar.setAction(actionText) {
                action()
            }
        }
        snackbar.show()
    }

    // Change the fab to show a delete icon
    fun toggleDeleteFab(active: Boolean = false) {
        val addFab = binding.fab
        val deleteFab = binding.fabDelete
        val bottomBarId = binding.bottomBar.id
        val addParams: CoordinatorLayout.LayoutParams =
            addFab.layoutParams as CoordinatorLayout.LayoutParams
        val deleteParams: CoordinatorLayout.LayoutParams =
            deleteFab.layoutParams as CoordinatorLayout.LayoutParams

        // Case 1: add fab currently hidden, it needs to be active
        if (!active && addFab.visibility == View.GONE) {
            // Change anchors to avoid visual problems
            addParams.anchorId = bottomBarId
            addFab.layoutParams = addParams

            deleteParams.anchorId = View.NO_ID
            deleteFab.layoutParams = deleteParams

            addFab.visibility = View.VISIBLE
            deleteFab.visibility = View.GONE
            deleteFab.applyLoopingAnimatedVectorDrawable(
                R.drawable.animated_delete,
                3000L,
                true
            )
            addFab.applyLoopingAnimatedVectorDrawable(
                R.drawable.animated_add_event,
                5000L
            )
        }

        // Case 2: delete fab currently hidden, it needs to be active
        if (active && deleteFab.visibility == View.GONE) {
            // Change anchors to avoid visual problems
            addParams.anchorId = View.NO_ID
            addFab.layoutParams = addParams

            deleteParams.anchorId = bottomBarId
            deleteFab.layoutParams = deleteParams

            addFab.visibility = View.GONE
            deleteFab.visibility = View.VISIBLE
            deleteFab.applyLoopingAnimatedVectorDrawable(
                R.drawable.animated_delete,
                3000L
            )
            addFab.applyLoopingAnimatedVectorDrawable(
                R.drawable.animated_add_event,
                5000L,
                true
            )
        }
    }

    // Ask contacts permission
    fun askContactsPermission(code: Int = 101): Boolean {
        return if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                code
            )
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Ask calendar permission
    fun askCalendarPermission(code: Int = 301): Boolean {
        return if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR),
                code
            )
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Ask notification permission
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun askNotificationPermission(code: Int = 201): Boolean {
        return if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                code
            )
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Manage user response to permission requests
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            // Contacts at startup, show a snackbar only for permission denied (don't ask again not selected)
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS))
                        showSnackbar(getString(R.string.missing_permission_contacts))
                } else if (grantResults.isNotEmpty() /* && grantResults[0] == PackageManager.PERMISSION_GRANTED */) {
                    // Show Bottom sheet for import
                    ImportContactsBottomSheet().showIfNotAdded(
                        supportFragmentManager,
                        ImportContactsBottomSheet.TAG
                    )
                }
            }
            // Contacts while trying to import from contacts
            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS))
                        showSnackbar(
                            getString(R.string.missing_permission_contacts),
                            actionText = getString(R.string.cancel),
                            action = fun() {
                                askContactsPermission()
                            })
                    else showSnackbar(getString(R.string.missing_permission_contacts_forever),
                        actionText = getString(R.string.title_settings),
                        action = fun() {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        })
                } else {
                    val contactImporter = ContactsImporter(this, null)
                    contactImporter.importContacts(this)
                }
            }
            // Notifications request at startup, plus contacts after
            201 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                        showSnackbar(
                            getString(R.string.missing_permission_notifications),
                            actionText = getString(R.string.cancel),
                            action =
                            fun() {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    askNotificationPermission()
                                }
                            })
                    else showSnackbar(
                        getString(R.string.missing_permission_notifications_forever),
                        actionText = getString(R.string.title_settings),
                        action = fun() {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        })
                }
                // Request contacts permission in every case
                askContactsPermission()
            }
            // Calendar permission when importing from calendar
            302 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR))
                        showSnackbar(
                            getString(R.string.missing_permission_calendar),
                            actionText = getString(R.string.cancel),
                            action = fun() {
                                askCalendarPermission()
                            })
                    else showSnackbar(
                        getString(R.string.missing_permission_calendar_forever),
                        actionText = getString(R.string.title_settings),
                        action = fun() {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        })
                }
            }
        }
    }
}
