const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const OWNER_UID = "owner_789";
const OWNER_EMAIL = "lofimusic492@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRecordPayload(uid, overrides = {}) {
  const past = new Date(Date.now() - 2000);
  return {
    userId: uid,
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

test("Unauthenticated user cannot read or write truck_weight_records or app_settings", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("truck_weight_records").get());
  await assertFails(
    unauthDb
      .collection("truck_weight_records")
      .doc("rec_1")
      .set(validRecordPayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection("app_settings").doc("runtime_config").get());
  await assertFails(unauthDb.collection("app_settings").doc("owner_admin").get());
});

test("Operator: valid create, Telegram metadata partial update, and scoped query succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "operator@example.com",
    email_verified: true,
  }).firestore();

  // Create first record (no previousWeight, no id)
  await assertSucceeds(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_1")
      .set(validRecordPayload(ALICE_UID))
  );

  // Create second record with previousWeight & GREEN_SIGNAL
  await assertSucceeds(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_2")
      .set(
        validRecordPayload(ALICE_UID, {
          truckWeight: 6060,
          previousWeight: 6050,
          weightDifference: 10,
          alertStatus: "GREEN_SIGNAL",
        })
      )
  );

  // Scoped query matching Kotlin repository
  await assertSucceeds(
    aliceDb
      .collection("truck_weight_records")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  // Partial update of Telegram message metadata succeeds
  await assertSucceeds(
    aliceDb.collection("truck_weight_records").doc("rec_1").update({
      telegramMessageId: "987654",
      documentImageTelegramFileId: "AgACAgUAAxkBAA",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Operator: rejects @DocumentId field, future timestamps, unfiltered list, and tampering weight", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "operator@example.com",
    email_verified: true,
  }).firestore();

  // Reject extra 'id' field
  await assertFails(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_bad_id")
      .set(validRecordPayload(ALICE_UID, { id: "rec_bad_id" }))
  );

  // Reject future timestamp
  await assertFails(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_future")
      .set(
        validRecordPayload(ALICE_UID, {
          createdAt: new Date(Date.now() + 60000),
        })
      )
  );

  // Reject invalid alertStatus
  await assertFails(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_bad_status")
      .set(validRecordPayload(ALICE_UID, { alertStatus: "CRITICAL" }))
  );

  // Reject unfiltered list query for non-owner
  await assertFails(aliceDb.collection("truck_weight_records").get());

  // Seed valid doc and verify operator cannot tamper truckWeight or createdAt
  await assertSucceeds(
    aliceDb
      .collection("truck_weight_records")
      .doc("rec_1")
      .set(validRecordPayload(ALICE_UID))
  );
  await assertFails(
    aliceDb.collection("truck_weight_records").doc("rec_1").update({
      truckWeight: 9999,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("truck_weight_records").doc("rec_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Cross-user isolation: Alice cannot read, update, or delete Bob's record", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("truck_weight_records")
      .doc("bob_rec")
      .set(validRecordPayload(BOB_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "operator@example.com",
    email_verified: true,
  }).firestore();

  await assertFails(aliceDb.collection("truck_weight_records").doc("bob_rec").get());
  await assertFails(
    aliceDb.collection("truck_weight_records").doc("bob_rec").update({
      notes: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(aliceDb.collection("truck_weight_records").doc("bob_rec").delete());
});

test("Owner-only admin settings & email spoofing prevention", async () => {
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
  const ownerAdminPayload = {
    ownerEmail: OWNER_EMAIL,
    ocrStrictFinalWeight: true,
    telegramChatIdLabel: "-1001234567890",
    firebaseConfigNote: "Production",
    createdAt: past,
    updatedAt: past,
  };

  // Spoofed email (email_verified: false) MUST fail
  const spoofedOwnerDb = testEnv.authenticatedContext("spoof_uid", {
    email: OWNER_EMAIL,
    email_verified: false,
  }).firestore();
  await assertFails(
    spoofedOwnerDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertFails(
    spoofedOwnerDb.collection("app_settings").doc("owner_admin").set(ownerAdminPayload)
  );
  await assertFails(spoofedOwnerDb.collection("truck_weight_records").get());

  // Regular operator cannot write runtime_config or read/write owner_admin
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "operator@example.com",
    email_verified: true,
  }).firestore();
  await assertFails(
    aliceDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertFails(aliceDb.collection("app_settings").doc("owner_admin").get());

  // Verified owner can write runtime_config, owner_admin, and list all records
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("runtime_config").set(runtimePayload)
  );
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("owner_admin").set(ownerAdminPayload)
  );
  await assertSucceeds(
    ownerDb.collection("app_settings").doc("runtime_config").update({
      greenMaxKg: 16,
      orangeMaxKg: 22,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(ownerDb.collection("truck_weight_records").get());

  // Regular operator CAN read runtime_config thresholds
  await assertSucceeds(aliceDb.collection("app_settings").doc("runtime_config").get());
});
