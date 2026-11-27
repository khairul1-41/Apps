package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.OwnerPolicy
import com.example.data.model.AppUpdateRelease
import com.example.data.model.AuthorizedMember
import com.example.data.model.UiState
import com.example.data.model.UserRole
import com.example.ui.components.AppUpdateAdminCard
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.SteelAccent
import com.example.ui.theme.SteelContainer
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.WarningOrangeContainer
import com.example.ui.viewmodel.AppUpdatePublishState
import com.example.ui.viewmodel.StagedApkState
import com.example.ui.viewmodel.TeamActionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamManagementScreen(
    callerEmail: String,
    callerRole: UserRole,
    membersState: UiState<List<AuthorizedMember>>,
    actionState: TeamActionState,
    appUpdateRelease: AppUpdateRelease?,
    stagedApkState: StagedApkState,
    appUpdatePublishState: AppUpdatePublishState,
    hasDriveAccess: Boolean,
    onDriveTokenGranted: (String) -> Unit,
    onDriveAuthError: (String) -> Unit,
    onAddMember: (email: String, displayName: String, role: UserRole) -> Unit,
    onUpdateRole: (AuthorizedMember, UserRole) -> Unit,
    onDeleteMember: (AuthorizedMember) -> Unit,
    onDismissActionState: () -> Unit,
    onPickApkUri: (Uri) -> Unit,
    onClearStagedApk: () -> Unit,
    onPublishAppUpdate: (
        versionCodeText: String,
        versionNameText: String,
        releaseNotesText: String,
        manualHttpsUrlText: String,
        isMandatory: Boolean,
        driveAccessTokenOverride: String?
    ) -> Unit,
    onRemovePublishedUpdate: () -> Unit,
    onDismissAppUpdatePublishState: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val canManageUsers = callerRole.canManageUsers
    val canManageAdmins = callerRole.canManageAdmins

    var emailInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var selectedNewRole by remember { mutableStateOf(UserRole.USER) }
    var memberPendingDelete by remember { mutableStateOf<AuthorizedMember?>(null) }

    LaunchedEffect(actionState) {
        if (actionState is TeamActionState.Success) {
            emailInput = ""
            nameInput = ""
            selectedNewRole = UserRole.USER
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Admin Panel & Team Access",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (canManageAdmins) {
                                "Owner: Manage Admins, Users & App Updates"
                            } else {
                                "Admin: Manage Team Users & App Updates"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("team_management_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PureWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyPrimary)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateBackground)
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (!canManageUsers) {
                Card(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 520.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = WarningOrangeContainer),
                    border = BorderStroke(1.dp, WarningOrange)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = WarningOrange)
                            Text(
                                text = "Admin Access Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Only the Owner or an assigned Admin can manage team members. Your account ($callerEmail) has standard shared User access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary
                        )
                        Button(
                            onClick = onBackClick,
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Return")
                        }
                    }
                }
            } else {
                val members = (membersState as? UiState.Success)?.data.orEmpty()
                val adminCount = members.count { it.resolvedRole == UserRole.ADMIN }
                val userCount = members.count { it.resolvedRole == UserRole.USER }
                val isSaving = actionState is TeamActionState.Saving

                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Summary Banner
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = NavyPrimary),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Group,
                                        contentDescription = null,
                                        tint = SignalGreen,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Multi-User Shared Access Directory",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = PureWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "1 Owner • $adminCount Admins • $userCount Users",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = SignalGreen
                                        )
                                    }
                                }
                                Text(
                                    text = "All authorized Users and Admins share read/scan access to every vehicle weighbridge record logged by the team.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateSurfaceVariant
                                )
                            }
                        }
                    }

                    // App Update Distribution Card (Owner & Admin Only)
                    item {
                        AppUpdateAdminCard(
                            currentRelease = appUpdateRelease,
                            stagedApkState = stagedApkState,
                            publishState = appUpdatePublishState,
                            hasDriveAccess = hasDriveAccess,
                            onDriveTokenGranted = onDriveTokenGranted,
                            onDriveAuthError = onDriveAuthError,
                            onPickApkUri = onPickApkUri,
                            onClearStagedApk = onClearStagedApk,
                            onPublishUpdate = onPublishAppUpdate,
                            onRemovePublishedUpdate = onRemovePublishedUpdate,
                            onDismissPublishState = onDismissAppUpdatePublishState
                        )
                    }

                    // Add Member Form
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            border = BorderStroke(1.dp, SlateBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PersonAdd,
                                        contentDescription = null,
                                        tint = NavyPrimary
                                    )
                                    Text(
                                        text = if (canManageAdmins) "Add User or New Admin" else "Add New Team User",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NavyPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedTextField(
                                    value = emailInput,
                                    onValueChange = {
                                        emailInput = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Google Email Address *") },
                                    placeholder = { Text("operator@gmail.com") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Email, contentDescription = null)
                                    },
                                    singleLine = true,
                                    enabled = !isSaving,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Email,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("team_add_email_input")
                                )

                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = {
                                        nameInput = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Display Name / Gate Label (Optional)") },
                                    placeholder = { Text("e.g. Gate 1 Operator") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Person, contentDescription = null)
                                    },
                                    singleLine = true,
                                    enabled = !isSaving,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("team_add_name_input")
                                )

                                if (canManageAdmins) {
                                    Text(
                                        text = "Assign Role",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = NavyPrimary
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        FilterChip(
                                            selected = selectedNewRole == UserRole.USER,
                                            onClick = { selectedNewRole = UserRole.USER },
                                            label = { Text("User (Operator)") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Filled.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NavyPrimary,
                                                selectedLabelColor = PureWhite,
                                                selectedLeadingIconColor = PureWhite
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("team_role_chip_user")
                                        )
                                        FilterChip(
                                            selected = selectedNewRole == UserRole.ADMIN,
                                            onClick = { selectedNewRole = UserRole.ADMIN },
                                            label = { Text("Admin") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Filled.AdminPanelSettings,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SteelAccent,
                                                selectedLabelColor = PureWhite,
                                                selectedLeadingIconColor = PureWhite
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("team_role_chip_admin")
                                        )
                                    }
                                    Text(
                                        text = "Admins can add or delete Users and manage shared vehicle records. Only the Owner can make or remove Admins.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateTextSecondary
                                    )
                                } else {
                                    Surface(
                                        color = NavyContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "New members added by an Admin receive standard User role access (scan slips & access all shared vehicle records).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NavyPrimary,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        onAddMember(
                                            emailInput,
                                            nameInput,
                                            if (canManageAdmins) selectedNewRole else UserRole.USER
                                        )
                                    },
                                    enabled = !isSaving && emailInput.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("team_add_submit_button")
                                ) {
                                    if (isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PureWhite
                                        )
                                    } else {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (canManageAdmins && selectedNewRole == UserRole.ADMIN) {
                                                "Add Admin"
                                            } else {
                                                "Add User"
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Action Feedback Banner
                    item {
                        when (actionState) {
                            is TeamActionState.Success -> {
                                Surface(
                                    color = SignalGreenContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, SignalGreen)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SignalGreen)
                                        Text(actionState.message, color = NavyPrimary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            is TeamActionState.Error -> {
                                Surface(
                                    color = AlertRedContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, AlertRed)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AlertRed)
                                        Text(actionState.message, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }

                    // Pinned Permanent Owner Card
                    item {
                        Text(
                            text = "Authorized Team Accounts",
                            style = MaterialTheme.typography.titleMedium,
                            color = NavyPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SignalGreenContainer),
                            border = BorderStroke(1.dp, SignalGreen)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = "Owner",
                                        tint = SignalGreen
                                    )
                                    Column {
                                        Text(
                                            text = "Primary Owner",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = NavyPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = OwnerPolicy.OWNER_EMAIL,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = NavyPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Surface(
                                    color = SignalGreen,
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        text = "OWNER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Dynamic Team Directory List
                    when (membersState) {
                        is UiState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = NavyPrimary)
                                }
                            }
                        }

                        is UiState.Error -> {
                            item {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = AlertRedContainer),
                                    border = BorderStroke(1.dp, AlertRed)
                                ) {
                                    Text(
                                        text = membersState.message,
                                        color = AlertRed,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }

                        is UiState.Success -> {
                            if (members.isEmpty()) {
                                item {
                                    Card(
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                                        border = BorderStroke(1.dp, SlateBorder)
                                    ) {
                                        Text(
                                            text = "No extra Admins or Users added yet. Enter a colleague's Google email above to grant access.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SlateTextSecondary,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            } else {
                                items(members, key = { it.email.ifBlank { it.id } }) { member ->
                                    TeamMemberCard(
                                        member = member,
                                        callerCanManageAdmins = canManageAdmins,
                                        isBusy = isSaving,
                                        onToggleAdmin = {
                                            val nextRole = if (member.resolvedRole == UserRole.ADMIN) {
                                                UserRole.USER
                                            } else {
                                                UserRole.ADMIN
                                            }
                                            onUpdateRole(member, nextRole)
                                        },
                                        onRequestDelete = { memberPendingDelete = member }
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    val pending = memberPendingDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { memberPendingDelete = null },
            title = { Text("Delete ${pending.resolvedRole.displayTitle}?") },
            text = {
                Text("Remove ${pending.email} from TruckWeight Guard? They will immediately lose access to shared truck weight records.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memberPendingDelete = null
                        onDeleteMember(pending)
                    }
                ) {
                    Text("Delete", color = AlertRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TeamMemberCard(
    member: AuthorizedMember,
    callerCanManageAdmins: Boolean,
    isBusy: Boolean,
    onToggleAdmin: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val isAdmin = member.resolvedRole == UserRole.ADMIN
    val canDeleteThisMember = callerCanManageAdmins || !isAdmin

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("team_member_card_${member.email}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.displayName?.takeIf { it.isNotBlank() } ?: member.email.substringBefore("@"),
                        style = MaterialTheme.typography.titleSmall,
                        color = NavyPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = member.email,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = SlateTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (member.addedByEmail.isNotBlank()) {
                        Text(
                            text = "Added by ${member.addedByEmail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateTextSecondary
                        )
                    }
                }

                Surface(
                    color = if (isAdmin) SteelContainer else NavyContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = if (isAdmin) "ADMIN" else "USER",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAdmin) SteelAccent else NavyPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callerCanManageAdmins) {
                    OutlinedButton(
                        onClick = onToggleAdmin,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isAdmin) WarningOrange else SteelAccent),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("team_toggle_admin_${member.email}")
                    ) {
                        Icon(
                            imageVector = if (isAdmin) Icons.Filled.Person else Icons.Filled.AdminPanelSettings,
                            contentDescription = null,
                            tint = if (isAdmin) WarningOrange else SteelAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAdmin) "Remove Admin" else "Make Admin",
                            color = if (isAdmin) WarningOrange else SteelAccent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (isAdmin) {
                    Surface(
                        color = SteelContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = SteelAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Managed by Owner",
                                style = MaterialTheme.typography.labelSmall,
                                color = NavyPrimary
                            )
                        }
                    }
                }

                if (canDeleteThisMember) {
                    OutlinedButton(
                        onClick = onRequestDelete,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("team_delete_${member.email}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete ${member.email}",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
