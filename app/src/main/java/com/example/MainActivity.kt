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
import com.example.core.OwnerPolicy
import com.example.core.attemptAutoSignIn
import com.example.core.authStateFlow
import com.example.core.configureAppCheck
import com.example.core.signOutUser
import com.example.data.ocr.WeighbridgeOcrService
import com.example.data.remote.TelegramDispatchResult
import com.example.data.remote.TelegramRelayService
import com.example.data.repository.TruckWeightRepository
import com.example.ui.screens.AnalysisScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.CropPreviewScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
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
        val email = user.email.orEmpty()
        val isOwner = OwnerPolicy.isOwner(user)

        key(uid) {
            val viewModel: TruckWeightViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        TruckWeightViewModel(
                            repository = TruckWeightRepository(db),
                            ocrService = WeighbridgeOcrService(app),
                            telegramRelayService = TelegramRelayService(app),
                            currentUserId = uid,
                            userEmail = email,
                            isOwner = isOwner
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
    val workingImageUri by viewModel.workingImageUri.collectAsStateWithLifecycle()
    val isProcessingImage by viewModel.isProcessingImage.collectAsStateWithLifecycle()
    val imageError by viewModel.imageError.collectAsStateWithLifecycle()
    val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
    val retryingTelegramRecordId by viewModel.retryingTelegramRecordId.collectAsStateWithLifecycle()
    val settingsActionState by viewModel.settingsActionState.collectAsStateWithLifecycle()
    val transientMessage by viewModel.transientMessage.collectAsStateWithLifecycle()

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
                                isOwner = viewModel.isOwner,
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
                                onDeleteRecord = viewModel::deleteRecord
                            )
                        }

                        composable(AppRoutes.SETTINGS) {
                            SettingsScreen(
                                userEmail = viewModel.userEmail,
                                isOwner = viewModel.isOwner,
                                runtimeSettings = runtimeSettings,
                                ownerAdminConfig = ownerAdminConfig,
                                actionState = settingsActionState,
                                onSaveOwnerSettings = viewModel::saveOwnerSettings,
                                onDismissActionState = viewModel::clearSettingsActionState
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
                                onUpdateForm = viewModel::updateAnalysisForm,
                                onConfirmAndSave = viewModel::confirmAndSaveRecord,
                                onRetryOcr = viewModel::runOcrAnalysis,
                                onEnterManually = viewModel::openManualEntryFromError,
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
    }
}
