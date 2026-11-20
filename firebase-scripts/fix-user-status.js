// Veri düzeltme: e-posta ile kayıt olan kullanıcılara "status" alanı yazılmıyordu
// (kayıt ekranı eskiden "isActive" yazıyordu; Firestore bunu "active" adıyla kaydeder.
// Askıya alma kontrolü ise "status" alanına bakıyor).
// Bu script "status" alanı olmayan kullanıcılara status: "active" ekler.
//
// Çalıştırma:
//   npm run fix-status:dry  -> sadece hangi kullanıcıların düzeltileceğini listeler
//   npm run fix-status      -> Firestore'a yazar
const { db, dryRun } = require("./firebase");

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir şey yazılmayacak\n" : "Firestore'a yazılıyor...\n");

  const users = await db.collection("users").get();
  const batch = db.batch();
  let fixCount = 0;

  for (const doc of users.docs) {
    const data = doc.data();
    if (data.status) continue; // Zaten "active" veya "suspended" yazılı

    // Eski "active: false" (veya "isActive: false") kaydı varsa askıya alınmış say, yoksa aktif
    const status = data.active === false || data.isActive === false ? "suspended" : "active";
    console.log(`  ${data.email || doc.id} -> status: "${status}"`);
    batch.update(doc.ref, { status });
    fixCount++;
  }

  if (fixCount > 0 && !dryRun) await batch.commit();
  console.log(`\n${users.size} kullanıcıdan ${fixCount} tanesi ${dryRun ? "düzeltilecek" : "düzeltildi"}.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
