// Test hesapları: 4 yönetici + 6 kullanıcı (isterdeki "en az 10 kullanıcı kaydı" için; asıl admin hesabı hariç).
//
// Çalıştırma:
//   npm run seed-test-accounts:dry  -> hiçbir şey oluşturmaz, sadece listeler
//   npm run seed-test-accounts      -> Firebase Auth hesaplarını ve Firestore kullanıcı belgelerini oluşturur
//
// - E-postalar test için ayrılmış "example.com" alan adındadır; gerçek bir kişiye e-posta gitmez.
// - Şifreler her hesap için rastgele üretilir ve SADECE yerel "test-accounts.local.json" dosyasına yazılır
//   (bu dosya .gitignore'dadır; GitHub'a gitmez, kimseyle paylaşmayın).
// - Tekrar çalıştırmak güvenlidir: e-postası zaten kayıtlı hesap atlanır, şifresi değiştirilmez.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { admin, db, dryRun } = require("./firebase");

const accounts = [
  // Yöneticiler
  { fullName: "Elif Yılmaz", email: "elif.yilmaz@example.com", role: "admin" },
  { fullName: "Mehmet Kaya", email: "mehmet.kaya@example.com", role: "admin" },
  { fullName: "Zeynep Demir", email: "zeynep.demir@example.com", role: "admin" },
  { fullName: "Can Öztürk", email: "can.ozturk@example.com", role: "admin" },
  // Kullanıcılar
  { fullName: "Ayşe Çelik", email: "ayse.celik@example.com", role: "user" },
  { fullName: "Burak Şahin", email: "burak.sahin@example.com", role: "user" },
  { fullName: "Deniz Arslan", email: "deniz.arslan@example.com", role: "user" },
  { fullName: "Emre Koç", email: "emre.koc@example.com", role: "user" },
  { fullName: "Selin Aydın", email: "selin.aydin@example.com", role: "user" },
  { fullName: "Oğuz Yıldız", email: "oguz.yildiz@example.com", role: "user" },
];

const CREDENTIALS_FILE = path.join(__dirname, "test-accounts.local.json");

// Okunaklı ama tahmin edilemez şifre: 12 karakter (harf + rakam), kayıt ekranındaki 6 karakter kuralını karşılar
function generatePassword() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  return Array.from(crypto.randomBytes(12), (b) => alphabet[b % alphabet.length]).join("");
}

async function exists(email) {
  try {
    return await admin.auth().getUserByEmail(email);
  } catch (e) {
    if (e.code === "auth/user-not-found") return null;
    throw e;
  }
}

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir hesap oluşturulmayacak\n" : "Hesaplar oluşturuluyor...\n");

  // Daha önce kaydedilmiş şifreler korunur (yeniden çalıştırmada dosya silinmesin)
  const saved = fs.existsSync(CREDENTIALS_FILE) ? JSON.parse(fs.readFileSync(CREDENTIALS_FILE, "utf8")) : [];
  let created = 0;

  for (const account of accounts) {
    const label = `${account.role === "admin" ? "Yönetici" : "Kullanıcı"} | ${account.fullName} | ${account.email}`;
    if (await exists(account.email)) {
      console.log(`  = Zaten var, atlandı: ${label}`);
      continue;
    }
    console.log(`  + Oluşturulacak: ${label}`);
    if (dryRun) {
      created++;
      continue;
    }

    const password = generatePassword();
    const user = await admin.auth().createUser({
      email: account.email,
      password,
      displayName: account.fullName,
      emailVerified: false,
    });
    // Uygulamanın beklediği kullanıcı belgesi (RegisterActivity ile aynı alanlar)
    await db.collection("users").doc(user.uid).set({
      uid: user.uid,
      fullName: account.fullName,
      email: account.email,
      role: account.role,
      status: "active",
      createdAt: Date.now(),
    });
    saved.push({ email: account.email, password, role: account.role, fullName: account.fullName });
    created++;
  }

  if (!dryRun && created > 0) {
    fs.writeFileSync(CREDENTIALS_FILE, JSON.stringify(saved, null, 2), "utf8");
    console.log(`\nŞifreler kaydedildi: ${CREDENTIALS_FILE}`);
  }
  console.log(`\n${created} hesap ${dryRun ? "oluşturulacak" : "oluşturuldu"}.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
