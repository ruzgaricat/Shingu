// Ev odalarına göre 5 kategori ekler: Yatak Odası, Mutfak, Banyo, Oturma Odası, Balkon.
//
// Çalıştırma:
//   npm run seed-home-categories:dry  -> hiçbir şey yazmaz, sadece eklenecekleri listeler
//   npm run seed-home-categories      -> Firestore'a yazar
//
// Tekrar çalıştırmak güvenlidir: aynı isimde kategori zaten varsa (büyük/küçük harf farkı dahil)
// yenisi eklenmez. Kategori yapısı uygulamadaki ile aynıdır: { name, imageUrl, createdAt }
const { db, dryRun } = require("./firebase");

const categories = [
  { name: "Yatak Odası", imageUrl: "https://picsum.photos/seed/yatak-odasi/300/150" },
  { name: "Mutfak", imageUrl: "https://picsum.photos/seed/mutfak/300/150" },
  { name: "Banyo", imageUrl: "https://picsum.photos/seed/banyo/300/150" },
  { name: "Oturma Odası", imageUrl: "https://picsum.photos/seed/oturma-odasi/300/150" },
  { name: "Balkon", imageUrl: "https://picsum.photos/seed/balkon/300/150" },
];

// Türkçe büyük/küçük harf duyarsız karşılaştırma ("oturma odası" ile "Oturma Odası" aynı sayılır)
const normalize = (name) => name.trim().toLocaleLowerCase("tr-TR");

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir şey yazılmayacak\n" : "Firestore'a yazılıyor...\n");

  const existing = await db.collection("categories").get();
  const existingNames = new Set(existing.docs.map((d) => normalize(d.get("name") || "")));

  let added = 0;
  const now = Date.now();
  for (const category of categories) {
    if (existingNames.has(normalize(category.name))) {
      console.log(`  = Zaten var, atlandı: ${category.name}`);
      continue;
    }
    console.log(`  + Eklenecek: ${category.name}`);
    if (!dryRun) await db.collection("categories").add({ ...category, createdAt: now });
    added++;
  }

  console.log(`\n${added} kategori ${dryRun ? "eklenecek" : "eklendi"}, ${categories.length - added} tanesi zaten vardı.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
