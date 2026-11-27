package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.OwnerFirebaseConfigStore
import com.example.core.OwnerPolicy
import com.example.core.attemptAutoSignIn
import com.example.core.authStateFlow
import com.example.core.configureAppCheck
import com.example.core.signOutUser
import com.example.data.model.MemberAccessState
import com.example.data.ocr.WeighbridgeOcrService
import com.example.data.remote.AppUpdateUploadService
import com.example.data.remote.TelegramRelayService
import com.example.data.repository.TruckWeightRepository
import com.example.ui.components.AppUpdatePopupDialog
import com.example.ui.components.launchSafeApkDownload
import com.example.ui.screens.AccessCheckingScreen
import com.example.ui.screens.AccessPendingScreen
import com.example.ui.screens.AnalysisScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.CropPreviewScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OwnerSettingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SlipTemplateMarkerScreen
import com.example.ui.screens.TeamManagementScreen
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.TruckWeightGuardTheme
import com.example.ui.viewmodel.TruckWeightViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

object AppRoutes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CAMERA = "camera"
    const val CROP_PREVIEW = "crop_preview"
    const val ANALYSIS = "analysis"
    const val OWNER_SETTINGS = "owner_settings"
    const val TEAM_MANAGEMENT = "team_management"
    const val SLIP_TEMPLATES = "slip_templates"
}

private data class TopTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopTabs = listOf(
    TopTabItem(
        route = AppRoutes.HOME,
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_home"
    ),
    TopTabItem(
        route = AppRoutes.HISTORY,
        label = "History",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history"
    ),
    TopTabItem(
        route = AppRoutes.SETTINGS,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before any UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            TruckWeightGuardTheme {
                TruckWeightGuardRoot()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("FIREBASE_APPCHECK_DEBUG_TOKEN")) {
            configureAppCheck(this, intent)
        }
    }
}

@Composable
private fun TruckWeightGuardRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {},
            onUnauthenticated = {},
            scope = scope
        )
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        val uid = user.uid
        val email = OwnerPolicy.normalizeEmail(user.email).ifEmpty { user.email.orEmpty() }
        var accessRefreshNonce by remember(uid) { mutableIntStateOf(0) }
        val databaseId = remember(context) { context.getString(R.string.firestore_database_id) }
        val accessRepository = remember(uid, accessRefreshNonce) {
            TruckWeightRepository(FirebaseFirestore.getInstance(databaseId))
        }
        val accessState by accessRepository.observeMemberAccess(user)
            .collectAsStateWithLifecycle(initialValue = MemberAccessState.Checking)

        when (val access = accessState) {
            MemberAccessState.Checking -> {
                AccessCheckingScreen()
            }

            is MemberAccessState.Unauthorized -> {
                AccessPendingScreen(
                    email = access.email.ifBlank { email },
                    errorMessage = access.errorMessage,
                    onRefreshAccess = { accessRefreshNonce++ },
                    onSignOut = {
                        signOutUser(credentialManager = credentialManager, scope = scope)
                    }
                )
            }

            is MemberAccessState.Authorized -> {
                key(uid, access.role) {
                    val viewModel: TruckWeightViewModel = viewModel(
                        key = "${uid}_${access.role.name}",
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY]) {
                                    "APPLICATION_KEY missing from CreationExtras"
                                }
                                val dbId = app.getString(R.string.firestore_database_id)
                                val db = FirebaseFirestore.getInstance(dbId)
                                TruckWeightViewModel(
                                    repository = TruckWeightRepository(db),
                                    ocrService = WeighbridgeOcrService(app),
                                    telegramRelayService = TelegramRelayService(app),
                                    appUpdateUploadService = AppUpdateUploadService(app),
                                    ownerConfigStore = OwnerFirebaseConfigStore(app),
                                    currentUserId = uid,
                                    userEmail = email,
                                    userRole = access.role
                                )
                            }
                        }
                    )

                    MainAuthenticatedScaffold(
                        viewModel = viewModel,
                        onSignOut = {
                            signOutUser(credentialManager = credentialManager, scope = scope)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainAuthenticatedScaffold(
    viewModel: TruckWeightViewModel,
    onSignOut: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val recordsState by viewModel.recordsState.collectAsStateWithLifecycle()
    val filteredRecords by viewModel.filteredRecords.collectAsStateWithLifecycle()
    val historyFilter by viewModel.historyFilter.collectAsStateWithLifecycle()
    val runtimeSettings by viewModel.runtimeSettings.collectAsStateWithLifecycle()
    val ownerAdminConfig by viewModel.ownerAdminConfig.collectAsStateWithLifecycle()
    val savedOwnerFirebaseConfig by viewModel.savedOwnerFirebaseConfig.collectAsStateWithLifecycle()
    val ownerConfigActionState by viewModel.ownerConfigActionState.collectAsStateWithLifecycle()
    val workingImageUri by viewModel.workingImageUri.collectAsStateWithLifecycle()
    val isProcessingImage by viewModel.isProcessingImage.collectAsStateWithLifecycle()
    val imageError by viewModel.imageError.collectAsStateWithLifecycle()
    val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
    val retryingTelegramRecordId by viewModel.retryingTelegramRecordId.collectAsStateWithLifecycle()
    val settingsActionState by viewModel.settingsActionState.collectAsStateWithLifecycle()
    val authorizedMembersState by viewModel.authorizedMembersState.collectAsStateWithLifecycle()
    val teamActionState by viewModel.teamActionState.collectAsStateWithLifecycle()
    val appUpdateRelease by viewModel.appUpdateRelease.collectAsStateWithLifecycle()
    val activeUpdatePopup by viewModel.activeUpdatePopup.collectAsStateWithLifecycle()
    val stagedApkState by viewModel.stagedApkState.collectAsStateWithLifecycle()
    val appUpdatePublishState by viewModel.appUpdatePublishState.collectAsStateWithLifecycle()
    val hasDriveAccess by viewModel.hasDriveAccess.collectAsStateWithLifecycle()
    val transientMessage by viewModel.transientMessage.collectAsStateWithLifecycle()
    val adsConfig by viewModel.adsConfig.collectAsStateWithLifecycle()
    val adsActionState by viewModel.adsActionState.collectAsStateWithLifecycle()
    val slipTemplates by viewModel.slipTemplates.collectAsStateWithLifecycle()
    val slipTemplateActionState by viewModel.slipTemplateActionState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var selectedVehicleKeyForHistory by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(transientMessage) {
        val msg = transientMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearTransientMessage()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.preparePickedImage(uri) {
                navController.navigate(AppRoutes.CROP_PREVIEW) {
                    launchSingleTop = true
                }
            }
        }
    }

    val showTopLevelNav = currentRoute in setOf(
        AppRoutes.HOME,
        AppRoutes.HISTORY,
        AppRoutes.SETTINGS
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp && showTopLevelNav

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = if (showTopLevelNav) {
                WindowInsets.navigationBars
            } else {
                WindowInsets(0, 0, 0, 0)
            },
            bottomBar = {
                if (showTopLevelNav && !useNavRail) {
                    NavigationBar(
                        containerColor = PureWhite,
                        tonalElevation = 4.dp
                    ) {
                        TopTabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(AppRoutes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NavyPrimary,
                                    selectedTextColor = NavyPrimary,
                                    indicatorColor = NavyContainer,
                                    unselectedIconColor = SlateTextSecondary,
                                    unselectedTextColor = SlateTextSecondary
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(containerColor = PureWhite) {
                        TopTabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(AppRoutes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.HOME
                    ) {
                        composable(AppRoutes.HOME) {
                            HomeScreen(
                                recordsState = recordsState,
                                userEmail = viewModel.userEmail,
                                userRole = viewModel.userRole,
                                availableUpdate = appUpdateRelease,
                                adsConfig = adsConfig,
                                onOpenUpdatePopupClick = viewModel::reopenUpdatePopup,
                                onScanDocumentClick = {
                                    navController.navigate(AppRoutes.CAMERA) {
                                        launchSingleTop = true
                                    }
                                },
                                onUploadGalleryClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onManageTeamClick = {
                                    navController.navigate(AppRoutes.TEAM_MANAGEMENT) {
                                        launchSingleTop = true
                                    }
                                },
                                onRecordClick = { record ->
                                    selectedVehicleKeyForHistory = record.vehicleKey
                                    navController.navigate(AppRoutes.HISTORY) {
                                        popUpTo(AppRoutes.HOME) { saveState = true }
                                        launchSingleTop = true
                                    }
                                },
                                onViewAllHistoryClick = {
                                    navController.navigate(AppRoutes.HISTORY) {
                                        popUpTo(AppRoutes.HOME) { saveState = true }
                                        launchSingleTop = true
                                    }
                                },
                                onSignOutClick = onSignOut
                            )
                        }

                        composable(AppRoutes.HISTORY) {
                            HistoryScreen(
                                recordsState = recordsState,
                                filteredRecords = filteredRecords,
                                filterState = historyFilter,
                                initialSelectedVehicleKey = selectedVehicleKeyForHistory,
                                onClearInitialVehicle = { selectedVehicleKeyForHistory = null },
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onAlertFilterChange = viewModel::updateAlertFilter,
                                onDateFilterChange = viewModel::updateDateFilter,
                                canDeleteRecord = viewModel::canDeleteRecord,
                                onDeleteRecord = viewModel::deleteRecord
                            )
                        }

                        composable(AppRoutes.SETTINGS) {
                            SettingsScreen(
                                userEmail = viewModel.userEmail,
                                userRole = viewModel.userRole,
                                runtimeSettings = runtimeSettings,
                                ownerAdminConfig = ownerAdminConfig,
                                savedOwnerFirebaseConfig = savedOwnerFirebaseConfig,
                                authorizedMembersState = authorizedMembersState,
                                actionState = settingsActionState,
                                adsConfig = adsConfig,
                                adsActionState = adsActionState,
                                slipTemplates = slipTemplates,
                                onOpenTeamManagement = {
                                    navController.navigate(AppRoutes.TEAM_MANAGEMENT) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenOwnerSettings = {
                                    navController.navigate(AppRoutes.OWNER_SETTINGS) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSlipTemplates = {
                                    navController.navigate(AppRoutes.SLIP_TEMPLATES) {
                                        launchSingleTop = true
                                    }
                                },
                                onSaveAdsConfig = viewModel::saveOwnerAdsConfig,
                                onDismissAdsActionState = viewModel::clearAdsActionState,
                                onSaveOwnerSettings = viewModel::saveOwnerSettings,
                                onDismissActionState = viewModel::clearSettingsActionState
                            )
                        }

                        composable(AppRoutes.TEAM_MANAGEMENT) {
                            TeamManagementScreen(
                                callerEmail = viewModel.userEmail,
                                callerRole = viewModel.userRole,
                                membersState = authorizedMembersState,
                                actionState = teamActionState,
                                appUpdateRelease = appUpdateRelease,
                                stagedApkState = stagedApkState,
                                appUpdatePublishState = appUpdatePublishState,
                                hasDriveAccess = hasDriveAccess,
                                onDriveTokenGranted = viewModel::setDriveAccessToken,
                                onDriveAuthError = viewModel::reportDriveAuthError,
                                onAddMember = viewModel::addTeamMember,
                                onUpdateRole = viewModel::setMemberRole,
                                onDeleteMember = viewModel::removeTeamMember,
                                onDismissActionState = viewModel::clearTeamActionState,
                                onPickApkUri = viewModel::stagePickedApk,
                                onClearStagedApk = viewModel::clearStagedApk,
                                onPublishAppUpdate = viewModel::publishAppUpdate,
                                onRemovePublishedUpdate = viewModel::removePublishedAppUpdate,
                                onDismissAppUpdatePublishState = viewModel::clearAppUpdatePublishState,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.OWNER_SETTINGS) {
                            OwnerSettingsScreen(
                                userEmail = viewModel.userEmail,
                                isOwner = viewModel.isOwner,
                                savedConfig = savedOwnerFirebaseConfig,
                                actionState = ownerConfigActionState,
                                onSaveConfig = viewModel::saveOwnerFirebaseConfig,
                                onClearConfig = viewModel::clearOwnerFirebaseConfig,
                                onDismissActionState = viewModel::clearOwnerConfigActionState,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.SLIP_TEMPLATES) {
                            SlipTemplateMarkerScreen(
                                userEmail = viewModel.userEmail,
                                isOwner = viewModel.isOwner,
                                templates = slipTemplates,
                                actionState = slipTemplateActionState,
                                onSaveTemplate = viewModel::saveSlipTemplate,
                                onDeleteTemplate = viewModel::deleteSlipTemplate,
                                onDismissActionState = viewModel::clearSlipTemplateActionState,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.CAMERA) {
                            CameraScreen(
                                onImageCaptured = { uri ->
                                    viewModel.setCapturedFileUri(uri)
                                    navController.navigate(AppRoutes.CROP_PREVIEW) {
                                        launchSingleTop = true
                                    }
                                },
                                onPickFromGalleryClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.CROP_PREVIEW) {
                            CropPreviewScreen(
                                imageUri = workingImageUri,
                                isProcessing = isProcessingImage,
                                errorMessage = imageError,
                                onRotateLeft = { viewModel.rotateWorkingImage(-90f) },
                                onRotateRight = { viewModel.rotateWorkingImage(90f) },
                                onApplyCrop = { rect -> viewModel.cropWorkingImage(rect) },
                                onResetOriginal = viewModel::resetWorkingImageToOriginal,
                                onRetake = {
                                    navController.navigate(AppRoutes.CAMERA) {
                                        popUpTo(AppRoutes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                onAnalyzeSlip = {
                                    viewModel.runOcrAnalysis()
                                    navController.navigate(AppRoutes.ANALYSIS) {
                                        launchSingleTop = true
                                    }
                                },
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.ANALYSIS) {
                            AnalysisScreen(
                                analysisState = analysisState,
                                previewImageUri = workingImageUri,
                                isRetryingTelegram = retryingTelegramRecordId != null,
                                onDuplicateOverrideChange = viewModel::setDuplicateOverrideConfirmed,
                                onConfirmAndSave = viewModel::confirmAndSaveRecord,
                                onRetryOcr = viewModel::runOcrAnalysis,
                                onRetryTelegram = viewModel::retryTelegramFromCompleted,
                                onGoToSettings = {
                                    navController.navigate(AppRoutes.SETTINGS) {
                                        popUpTo(AppRoutes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                onDoneHome = {
                                    viewModel.resetAnalysis()
                                    navController.navigate(AppRoutes.HOME) {
                                        popUpTo(AppRoutes.HOME) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onScanNext = {
                                    viewModel.resetAnalysis()
                                    navController.navigate(AppRoutes.CAMERA) {
                                        popUpTo(AppRoutes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }

        val pendingUpdate = activeUpdatePopup
        if (pendingUpdate != null) {
            AppUpdatePopupDialog(
                release = pendingUpdate,
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                onDownloadClick = {
                    launchSafeApkDownload(context, pendingUpdate.downloadUrl).onFailure { err ->
                        android.widget.Toast.makeText(
                            context,
                            err.localizedMessage ?: "Unable to open APK download link.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onLaterClick = {
                    viewModel.dismissUpdatePopup(pendingUpdate.versionCode)
                }
            )
        }
    }
}
