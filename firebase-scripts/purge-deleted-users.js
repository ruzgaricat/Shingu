// Kalıcı silme: uygulamada admin tarafından "silindi" olarak işaretlenen kullanıcıların
// Firebase Auth giriş hesabını ve Firestore'daki kullanıcı belgesini tamamen siler.
//
// Neden ayrı bir script? Başka bir kullanıcının giriş hesabını (Firebase Auth) silmek sunucu
// yetkisi gerektirir; Android uygulaması bunu yapamaz. Admin SDK ise bu yetkiye sahiptir.
// (Sepet, favoriler, adresler ve yorumlar uygulamada silme anında zaten temizlenir.)
//
// Çalıştırma:
//   npm run purge-deleted:dry  -> sadece silinecek hesapları listeler
//   npm run purge-deleted      -> kalıcı olarak siler (geri alınamaz)
const { admin, db, dryRun } = require("./firebase");

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir şey silinmeyecek\n" : "Kalıcı silme yapılıyor...\n");

  const deleted = await db.collection("users").where("status", "==", "deleted").get();
  for (const doc of deleted.docs) {
    console.log(`  - ${doc.data().email || doc.id}`);
    if (dryRun) continue;

    try {
      await admin.auth().deleteUser(doc.id);
    } catch (e) {
      // Giriş hesabı zaten yoksa belgeyi silmeye devam et
      if (e.code !== "auth/user-not-found") throw e;
    }
    // Belgenin kalan alt koleksiyonlarıyla birlikte silinmesi
    await db.recursiveDelete(doc.ref);
  }

  console.log(`\n${deleted.size} hesap ${dryRun ? "silinecek" : "kalıcı olarak silindi"}.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
