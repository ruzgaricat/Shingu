// Tüm scriptlerin ortak kullandığı bağlantı dosyası.
// Firebase Admin SDK, servis hesabı anahtarıyla bağlanır ve güvenlik kurallarına TAKILMAZ
// (kurallar sadece uygulamadan gelen isteklere uygulanır). Bu yüzden anahtar gizli tutulmalı.
const fs = require("fs");
const path = require("path");
const admin = require("firebase-admin");

const keyPath = path.join(__dirname, "serviceAccountKey.json");
if (!fs.existsSync(keyPath)) {
  console.error(
    "serviceAccountKey.json bulunamadı.\n" +
    "Firebase Console > Proje Ayarları > Hizmet Hesapları > 'Yeni özel anahtar oluştur' ile indirip\n" +
    `şu konuma bu adla kaydedin: ${keyPath}`
  );
  process.exit(1);
}

admin.initializeApp({ credential: admin.credential.cert(require(keyPath)) });

module.exports = {
  admin,
  db: admin.firestore(),
  // Komut satırında --dry-run verilirse scriptler hiçbir şey yazmaz, sadece ne yapacaklarını gösterir
  dryRun: process.argv.includes("--dry-run"),
};
