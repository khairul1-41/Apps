const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";

const OWNER_UID = "owner_789";
const OWNER_EMAIL = "lofimusic492@gmail.com";

const ADMIN_UID = "admin_101";
const ADMIN_EMAIL = "admin.ops@example.com";

const USER_A_UID = "alice_123";
const USER_A_EMAIL = "operator.a@example.com";

const USER_B_UID = "bob_456";
const USER_B_EMAIL = "operator.b@example.com";

const OUTSIDER_UID = "outsider_999";
const OUTSIDER_EMAIL = "stranger@example.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRecordPayload(uid, overrides = {}) {
  const past = new Date(Date.now() - 2000);
  return {
    userId: uid,
    createdByEmail: USER_A_EMAIL,
    createdByRole: "USER",
    vehicleNumber: "BUG T-11-1633",
    vehicleKey: "BUGT111633",
    truckWeight: 6050,
    weightDate: "22/11/2026",
    createdAt: past,
    updatedAt: past,
    weightDifference: 0,
    alertStatus: "NEUTRAL",
    ocrConfidence: 0.96,
    sourceLabel: "First Weight",
    ...overrides,
  };
}

function validMemberPayload(email, role, addedByEmail, addedByUid, displayName = "Team Member") {
  const past = new Date(Date.now() - 2000);
  return {
    email,
    displayName,
    role,
    addedByEmail,
    addedByUid,
    createdAt: past,
    updatedAt: past,
  };
}

async function seedAuthorizedTeam() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db
      .collection("authorized_users")
      .doc(ADMIN_EMAIL)
      .set(validMemberPayload(ADMIN_EMAIL, "ADMIN", OWNER_EMAIL, OWNER_UID, "Manager"));
    await db
      .collection("authorized_users")
      .doc(USER_A_EMAIL)
      .set(validMemberPayload(USER_A_EMAIL, "USER", ADMIN_EMAIL, ADMIN_UID, "Gate Operator A"));
    await db
      .collection("authorized_users")
      .doc(USER_B_EMAIL)
      .set(validMemberPayload(USER_B_EMAIL, "USER", OWNER_EMAIL, OWNER_UID, "Gate Operator B"));
  });
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated and unlisted/removed users cannot access records or settings", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("truck_weight_records").get());
  await assertFails(unauthDb.collection("authorized_users").doc(USER_A_EMAIL).get());
  await assertFails(unauthDb.collection("app_settings").doc("runtime_config").get());

  // Signed-in outsider NOT listed in authorized_users
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID, {
    email: OUTSIDER_EMAIL,
    email_verified: true,
  }).firestore();

  // Can check ONLY their own membership doc (non-existent), cannot list team or read records
  await assertSucceeds(outsiderDb.collection("authorized_users").doc(OUTSIDER_EMAIL).get());
  await assertFails(outsiderDb.collection("authorized_users").doc(USER_A_EMAIL).get());
  await assertFails(outsiderDb.collection("authorized_users").get());
  await assertFails(outsiderDb.collection("truck_weight_records").get());
  await assertFails(
    outsiderDb
      .collection("truck_weight_records")
      .doc("rec_unauth")
      .set(validRecordPayload(OUTSIDER_UID, { createdByEmail: OUTSIDER_EMAIL }))
  );
  await assertFails(outsiderDb.collection("app_settings").doc("runtime_config").get());
});

test("Owner can make/demote Admins, and Admins can add/delete Users but cannot promote or delete Admins", async () => {
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();

  // Owner adds a new ADMIN and a new USER
  await assertSucceeds(
    ownerDb
      .collection("authorized_users")
      .doc(ADMIN_EMAIL)
      .set(validMemberPayload(ADMIN_EMAIL, "ADMIN", OWNER_EMAIL, OWNER_UID, "Plant Manager"))
  );
  await assertSucceeds(
    ownerDb
      .collection("authorized_users")
      .doc(USER_A_EMAIL)
      .set(validMemberPayload(USER_A_EMAIL, "USER", OWNER_EMAIL, OWNER_UID, "Operator A"))
  );

  // Owner promotes USER A to ADMIN and later demotes back to USER
  await assertSucceeds(
    ownerDb.collection("authorized_users").doc(USER_A_EMAIL).update({
      role: "ADMIN",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    ownerDb.collection("authorized_users").doc(USER_A_EMAIL).update({
      role: "USER",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Admin can add USER B and list directory
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  await assertSucceeds(adminDb.collection("authorized_users").get());
  await assertSucceeds(
    adminDb
      .collection("authorized_users")
      .doc(USER_B_EMAIL)
      .set(validMemberPayload(USER_B_EMAIL, "USER", ADMIN_EMAIL, ADMIN_UID, "Operator B"))
  );

  // Admin CANNOT add another ADMIN (role escalation blocked)
  await assertFails(
    adminDb
      .collection("authorized_users")
      .doc("rogue.admin@example.com")
      .set(validMemberPayload("rogue.admin@example.com", "ADMIN", ADMIN_EMAIL, ADMIN_UID))
  );

  // Admin CANNOT promote USER A to ADMIN
  await assertFails(
    adminDb.collection("authorized_users").doc(USER_A_EMAIL).update({
      role: "ADMIN",
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Regular USER cannot list, add, or delete members
  const userADb = testEnv.authenticatedContext(USER_A_UID, {
    email: USER_A_EMAIL,
    email_verified: true,
  }).firestore();
  await assertFails(userADb.collection("authorized_users").get());
  await assertFails(
    userADb
      .collection("authorized_users")
      .doc("friend@example.com")
      .set(validMemberPayload("friend@example.com", "USER", USER_A_EMAIL, USER_A_UID))
  );
  await assertFails(userADb.collection("authorized_users").doc(USER_B_EMAIL).delete());

  // Admin CAN delete USER B, and USER B immediately loses record access
  await assertSucceeds(adminDb.collection("authorized_users").doc(USER_B_EMAIL).delete());
  const userBDb = testEnv.authenticatedContext(USER_B_UID, {
    email: USER_B_EMAIL,
    email_verified: true,
  }).firestore();
  await assertFails(userBDb.collection("truck_weight_records").get());

  // Admin CANNOT delete another ADMIN or Owner
  await ownerDb.collection("authorized_users").doc(USER_A_EMAIL).update({
    role: "ADMIN",
    updatedAt: new Date(Date.now() - 200),
  });
  await assertFails(adminDb.collection("authorized_users").doc(USER_A_EMAIL).delete());

  // Owner CAN delete an ADMIN
  await assertSucceeds(ownerDb.collection("authorized_users").doc(USER_A_EMAIL).delete());
});

test("Shared team vehicle records: all authorized Users & Admins can read records added by any member", async () => {
  await seedAuthorizedTeam();

  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const aliceDb = testEnv.authenticatedContext(USER_A_UID, {
    email: USER_A_EMAIL,
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(USER_B_UID, {
    email: USER_B_EMAIL,
    email_verified: true,
  }).firestore();

  // Admin creates a record, User A creates a record
  await assertSucceeds(
    adminDb
      .collection("truck_weight_records")
      .doc("admin_rec_1")
      .set(
        validRecordPayload(ADMIN_UID, {
          createdByEmail: ADMIN_EMAIL,
          createdByRole: "ADMIN",
          truckWeight: 6050,
        })
      )
  );
  await assertSucceeds(
    aliceDb
      .collection("truck_weight_records")
      .doc("alice_rec_1")
      .set(
        validRecordPayload(USER_A_UID, {
          createdByEmail: USER_A_EMAIL,
          createdByRole: "USER",
          truckWeight: 6060,
          previousWeight: 6050,
          weightDifference: 10,
          alertStatus: "GREEN_SIGNAL",
        })
      )
  );

  // User B can list all records (matching Kotlin query: orderBy createdAt DESC) and get Admin's & Alice's records!
  await assertSucceeds(
    bobDb.collection("truck_weight_records").orderBy("createdAt", "desc").get()
  );
  await assertSucceeds(bobDb.collection("truck_weight_records").doc("admin_rec_1").get());
  await assertSucceeds(bobDb.collection("truck_weight_records").doc("alice_rec_1").get());

  // User B cannot tamper or delete Alice's or Admin's record
  await assertFails(
    bobDb.collection("truck_weight_records").doc("alice_rec_1").update({
      truckWeight: 9999,
      updatedAt: new Date(Date.now() - 100),
    })
  );
  await assertFails(bobDb.collection("truck_weight_records").doc("alice_rec_1").delete());

  // Alice can update Telegram metadata on her own record and delete her own record
  await assertSucceeds(
    aliceDb.collection("truck_weight_records").doc("alice_rec_1").update({
      telegramMessageId: "987654",
      documentImageTelegramFileId: "AgACAgUAAxkBAA",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Admin can delete any record
  await assertSucceeds(adminDb.collection("truck_weight_records").doc("alice_rec_1").delete());
});

test("Validation bounds and Owner-only settings stay strictly enforced", async () => {
  await seedAuthorizedTeam();

  const aliceDb = testEnv.authenticatedContext(USER_A_UID, {
    email: USER_A_EMAIL,
    email_verified: true,
  }).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();

  // Reject @DocumentId field and future timestamps
  await assertFails(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_bad_id")
      .set(validRecordPayload(USER_A_UID, { id: "rec_bad_id" }))
  );
  await assertFails(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_future")
      .set(
        validRecordPayload(USER_A_UID, {
          createdAt: new Date(Date.now() + 60000),
        })
      )
  );

  const past = new Date(Date.now() - 2000);
  const runtimePayload = {
    neutralMaxKg: 4,
    greenMaxKg: 15,
    orangeMaxKg: 19,
    minOcrConfidence: 0.75,
    autoSendTelegram: true,
    telegramRelayUrl: "https://us-central1-demo.cloudfunctions.net/sendWeighbridgeToTelegram",
    createdAt: past,
    updatedAt: past,
  };

  // Neither regular User nor Admin can overwrite Owner runtime_config or read owner_admin
  await assertFails(
    aliceDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertFails(
    adminDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertFails(adminDb.collection("app_settings").doc("owner_admin").get());

  // Verified Owner can write settings and authorized User can read runtime_config
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertSucceeds(aliceDb.collection("app_settings").doc("runtime_config").get());
});

test("Only Owner and Admins can publish or delete app_update while all authorized Users can read pop-up alerts", async () => {
  await seedAuthorizedTeam();

  const past = new Date(Date.now() - 2000);
  const validSha256 = "a".repeat(64);
  const baseUpdate = {
    versionCode: 2,
    versionName: "1.1.0",
    fileName: "TruckWeightGuard-v1.1.0.apk",
    fileSizeBytes: 32000000,
    sha256Checksum: validSha256,
    downloadUrl: "https://firebasestorage.googleapis.com/v0/b/demo.firebasestorage.app/o/app_updates%2FTruckWeightGuard-v1.1.0.apk?alt=media",
    releaseNotes: "Locked OCR fields and added admin APK distribution.",
    isMandatory: false,
    createdAt: past,
    updatedAt: past,
  };

  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const aliceDb = testEnv.authenticatedContext(USER_A_UID, {
    email: USER_A_EMAIL,
    email_verified: true,
  }).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID, {
    email: OUTSIDER_EMAIL,
    email_verified: true,
  }).firestore();

  // Regular USER and outsider CANNOT create/upload app_update
  await assertFails(
    aliceDb.collection("app_settings").doc("app_update").set({
      ...baseUpdate,
      publishedByEmail: USER_A_EMAIL,
      publishedByRole: "ADMIN",
      publishedByUid: USER_A_UID,
    })
  );
  await assertFails(outsiderDb.collection("app_settings").doc("app_update").get());

  // Reject non-HTTPS downloadUrl or non-.apk fileName even from Owner
  await assertFails(
    ownerDb.collection("app_settings").doc("app_update").set({
      ...baseUpdate,
      downloadUrl: "http://insecure.example.com/app.apk",
      publishedByEmail: OWNER_EMAIL,
      publishedByRole: "OWNER",
      publishedByUid: OWNER_UID,
    })
  );
  await assertFails(
    ownerDb.collection("app_settings").doc("app_update").set({
      ...baseUpdate,
      fileName: "malware.exe",
      publishedByEmail: OWNER_EMAIL,
      publishedByRole: "OWNER",
      publishedByUid: OWNER_UID,
    })
  );

  // Owner CAN publish a valid APK update
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("app_update").set({
      ...baseUpdate,
      publishedByEmail: OWNER_EMAIL,
      publishedByRole: "OWNER",
      publishedByUid: OWNER_UID,
    })
  );

  // Authorized USER can read the active update pop-up notification, but cannot overwrite or delete it
  await assertSucceeds(aliceDb.collection("app_settings").doc("app_update").get());
  await assertFails(
    aliceDb.collection("app_settings").doc("app_update").update({
      versionCode: 99,
      updatedAt: new Date(Date.now() - 100),
    })
  );
  await assertFails(aliceDb.collection("app_settings").doc("app_update").delete());

  // Listed ADMIN CAN update and delete app_update
  await assertSucceeds(
    adminDb.collection("app_settings").doc("app_update").update({
      versionCode: 3,
      versionName: "1.2.0",
      publishedByEmail: ADMIN_EMAIL,
      publishedByRole: "ADMIN",
      publishedByUid: ADMIN_UID,
      updatedAt: new Date(Date.now() - 100),
    })
  );
  await assertSucceeds(adminDb.collection("app_settings").doc("app_update").delete());
});

test("Only Owner can publish ads_config or slip_templates and set App Check token while authorized members can read", async () => {
  await seedAuthorizedTeam();

  const past = new Date(Date.now() - 2000);
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const aliceDb = testEnv.authenticatedContext(USER_A_UID, {
    email: USER_A_EMAIL,
    email_verified: true,
  }).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID, {
    email: OUTSIDER_EMAIL,
    email_verified: true,
  }).firestore();

  // Owner updates runtime_config with a valid UUID appCheckDebugToken
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("runtime_config").set({
      neutralMaxKg: 4,
      greenMaxKg: 15,
      orangeMaxKg: 19,
      minOcrConfidence: 0.7,
      autoSendTelegram: true,
      appCheckDebugToken: "95158850-D125-410E-A80F-3CF42093E1BB",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Reject malformed non-UUID appCheckDebugToken
  await assertFails(
    ownerDb.collection("app_settings").doc("runtime_config").update({
      appCheckDebugToken: "not-a-valid-uuid",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Owner-only Ads Config: Admins and Users CANNOT write or delete ads_config
  const adsPayload = {
    adsEnabled: true,
    adsMode: "SPONSORED_CARD",
    adCodeSnippet: "",
    sponsorTitle: "Fleet GPS Partner",
    sponsorSubtitle: "Track diesel & weighbridge trips live",
    sponsorCtaLabel: "Visit",
    sponsorTargetUrl: "https://example.com/fleet",
    updatedByEmail: OWNER_EMAIL,
    createdAt: past,
    updatedAt: past,
  };
  await assertFails(
    adminDb.collection("app_settings").doc("ads_config").set({
      ...adsPayload,
      updatedByEmail: ADMIN_EMAIL,
    })
  );
  await assertFails(
    aliceDb.collection("app_settings").doc("ads_config").set({
      ...adsPayload,
      updatedByEmail: USER_A_EMAIL,
    })
  );
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("ads_config").set(adsPayload)
  );
  await assertSucceeds(aliceDb.collection("app_settings").doc("ads_config").get());
  await assertFails(adminDb.collection("app_settings").doc("ads_config").delete());

  // Custom Slip Templates: ONLY Owner can create/update/delete; all authorized members can read
  const templatePayload = {
    formatName: "New Scale Format 2026",
    headerHint: "BISMILLAH DIGITAL SCALE",
    vehicleBox: { left: 0.15, top: 0.25, right: 0.55, bottom: 0.35 },
    vehicleLabelHint: "Truck No:",
    weightBox: { left: 0.2, top: 0.5, right: 0.45, bottom: 0.6 },
    weightLabelHint: "Empty Wt:",
    dateBox: { left: 0.6, top: 0.2, right: 0.9, bottom: 0.3 },
    dateLabelHint: "Date:",
    notes: "Empty weight printed bottom-left",
    isActive: true,
    createdByEmail: OWNER_EMAIL,
    createdByUid: OWNER_UID,
    createdAt: past,
    updatedAt: past,
  };

  await assertFails(
    adminDb.collection("slip_templates").doc("tmpl_1").set({
      ...templatePayload,
      createdByEmail: ADMIN_EMAIL,
      createdByUid: ADMIN_UID,
    })
  );
  await assertFails(
    aliceDb.collection("slip_templates").doc("tmpl_1").set({
      ...templatePayload,
      createdByEmail: USER_A_EMAIL,
      createdByUid: USER_A_UID,
    })
  );
  await assertFails(outsiderDb.collection("slip_templates").get());

  await assertSucceeds(
    ownerDb.collection("slip_templates").doc("tmpl_1").set(templatePayload)
  );
  await assertSucceeds(aliceDb.collection("slip_templates").get());
  await assertFails(adminDb.collection("slip_templates").doc("tmpl_1").delete());
  await assertSucceeds(ownerDb.collection("slip_templates").doc("tmpl_1").delete());
});


