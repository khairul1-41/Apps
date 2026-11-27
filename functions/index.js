const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

admin.initializeApp();

// Store Telegram Bot Token and Chat ID exclusively in Firebase Secret Manager.
// Never expose these values in the Android APK or client-side code.
const telegramBotToken = defineSecret("TELEGRAM_BOT_TOKEN");
const telegramChatId = defineSecret("TELEGRAM_CHAT_ID");

const NAMED_DATABASE_ID =
  "ai-studio-android-truckwei-34ca8266-0529-4860-bee6-fb6aa1a81a5c";

function formatAlertBadge(alertStatus) {
  switch (alertStatus) {
    case "GREEN_SIGNAL":
      return "🟢 GREEN SIGNAL";
    case "ORANGE_WARNING":
      return "🟠 ORANGE WARNING";
    case "RED_ALERT":
      return "🔴 RED ALERT";
    default:
      return "⚪ NEUTRAL";
  }
}

exports.sendWeighbridgeToTelegram = onRequest(
  {
    region: "asia-southeast1",
    secrets: [telegramBotToken, telegramChatId],
    cors: false,
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ ok: false, error: "Method not allowed" });
      return;
    }

    // 1. Verify Firebase Auth ID token from Authorization: Bearer <token>
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ ok: false, error: "Missing Bearer token" });
      return;
    }
    const idToken = authHeader.slice("Bearer ".length).trim();

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(idToken);
    } catch (err) {
      res.status(401).json({ ok: false, error: "Invalid Firebase ID token" });
      return;
    }

    try {
      const {
        recordId,
        vehicleNumber,
        truckWeight,
        weightDate,
        previousWeight,
        weightDifference,
        alertStatus,
        imageBase64,
        imageMimeType,
      } = req.body || {};

      if (!recordId || !vehicleNumber || truckWeight === undefined || !imageBase64) {
        res.status(400).json({
          ok: false,
          error: "Missing required fields: recordId, vehicleNumber, truckWeight, imageBase64",
        });
        return;
      }

      const db = getFirestore(admin.app(), NAMED_DATABASE_ID);
      const recordRef = db.collection("truck_weight_records").doc(String(recordId));
      const recordSnap = await recordRef.get();

      if (!recordSnap.exists) {
        res.status(404).json({ ok: false, error: "Weight record not found" });
        return;
      }

      const recordData = recordSnap.data();
      const isOwner =
        decodedToken.email === "lofimusic492@gmail.com" &&
        decodedToken.email_verified === true;
      if (recordData.userId !== decodedToken.uid && !isOwner) {
        res.status(403).json({ ok: false, error: "Forbidden for this record" });
        return;
      }

      const token = telegramBotToken.value();
      const chatId = telegramChatId.value();
      if (!token || !chatId) {
        res.status(500).json({
          ok: false,
          error: "Telegram secrets not configured on backend",
        });
        return;
      }

      const prevText =
        previousWeight !== null && previousWeight !== undefined
          ? `${Number(previousWeight).toLocaleString("en-US")} KG`
          : "None (First Record)";
      const diffText = `${Number(weightDifference || 0).toLocaleString("en-US")} KG`;

      const caption = [
        `*TruckWeight Guard Verification*`,
        `Status: ${formatAlertBadge(alertStatus)}`,
        `Vehicle No: \`${String(vehicleNumber)}\``,
        `Truck Weight: *${Number(truckWeight).toLocaleString("en-US")} KG*`,
        `Previous Weight: ${prevText}`,
        `Difference: *${diffText}*`,
        `Weight Date: ${String(weightDate || "N/A")}`,
        `Record ID: \`${String(recordId)}\``,
      ].join("\n");

      const imageBuffer = Buffer.from(String(imageBase64), "base64");
      const ext = imageMimeType === "image/png" ? "png" : "jpg";
      const form = new FormData();
      form.append("chat_id", chatId);
      form.append("caption", caption);
      form.append("parse_mode", "Markdown");
      form.append(
        "photo",
        new Blob([imageBuffer], { type: imageMimeType || "image/jpeg" }),
        `weighbridge_${recordId}.${ext}`
      );

      const tgResponse = await fetch(
        `https://api.telegram.org/bot${token}/sendPhoto`,
        {
          method: "POST",
          body: form,
        }
      );
      const tgJson = await tgResponse.json();

      if (!tgResponse.ok || !tgJson.ok) {
        res.status(502).json({
          ok: false,
          error: tgJson.description || "Telegram Bot API rejected photo",
        });
        return;
      }

      const telegramMessageId = String(tgJson.result.message_id);
      const photos = tgJson.result.photo || [];
      const largestPhoto = photos[photos.length - 1] || {};
      const documentImageTelegramFileId = String(largestPhoto.file_id || "");

      await recordRef.update({
        telegramMessageId,
        documentImageTelegramFileId,
        updatedAt: FieldValue.serverTimestamp(),
      });

      res.status(200).json({
        ok: true,
        telegramMessageId,
        documentImageTelegramFileId,
      });
    } catch (err) {
      console.error("Telegram relay error:", err);
      res.status(500).json({ ok: false, error: err.message || "Internal error" });
    }
  }
);

exports.requestApkUploadUrl = onRequest(
  {
    region: "asia-southeast1",
    cors: false,
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ ok: false, error: "Method not allowed" });
      return;
    }

    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ ok: false, error: "Missing Bearer token" });
      return;
    }
    const idToken = authHeader.slice("Bearer ".length).trim();

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(idToken);
    } catch (err) {
      res.status(401).json({ ok: false, error: "Invalid Firebase ID token" });
      return;
    }

    try {
      const callerEmail = String(decodedToken.email || "").trim().toLowerCase();
      if (!callerEmail || decodedToken.email_verified !== true) {
        res.status(403).json({ ok: false, error: "Verified Google email required" });
        return;
      }

      const db = getFirestore(admin.app(), NAMED_DATABASE_ID);
      const isOwner = callerEmail === "lofimusic492@gmail.com";
      let isAdmin = false;
      if (!isOwner) {
        const memberSnap = await db.collection("authorized_users").doc(callerEmail).get();
        isAdmin = memberSnap.exists && memberSnap.data().role === "ADMIN";
      }

      if (!isOwner && !isAdmin) {
        res.status(403).json({
          ok: false,
          error: "Only the Owner or an Admin can upload APK updates",
        });
        return;
      }

      const { fileName, versionCode, contentType } = req.body || {};
      const safeFileName = String(fileName || "app-update.apk")
        .replace(/[^a-zA-Z0-9._-]/g, "_")
        .slice(0, 80);
      if (!safeFileName.toLowerCase().endsWith(".apk")) {
        res.status(400).json({ ok: false, error: "Only .apk files are permitted" });
        return;
      }

      const parsedVersionCode = Number(versionCode || 1);
      const objectPath = `app_updates/v${Math.max(1, Math.floor(parsedVersionCode))}_${Date.now()}_${safeFileName}`;
      const bucket = admin.storage().bucket();
      const file = bucket.file(objectPath);

      const [uploadUrl] = await file.getSignedUrl({
        version: "v4",
        action: "write",
        expires: Date.now() + 15 * 60 * 1000,
        contentType: contentType || "application/vnd.android.package-archive",
      });

      const downloadUrl = `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(objectPath)}?alt=media`;

      res.status(200).json({
        ok: true,
        uploadUrl,
        downloadUrl,
        objectPath,
      });
    } catch (err) {
      console.error("APK upload URL error:", err);
      res.status(500).json({ ok: false, error: err.message || "Internal error" });
    }
  }
);

