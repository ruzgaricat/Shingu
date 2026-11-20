// Veritabanı yedeği: tüm koleksiyonları ve alt koleksiyonları (sepet, favoriler, yorumlar, adresler...)
// tek bir JSON dosyasına aktarır. İsterlerdeki "Firebase'in kendi formatında veya export edilmiş
// veritabanı yedeği" maddesi için kullanılır.
//
// Çalıştırma: npm run backup
// Çıktı:      backups/shingu-backup-YYYY-MM-DD_HH-MM.json
//
// Dosya yapısı:
// {
//   "products": {
//     "<belgeId>": {
//       "data": { ...alanlar... },
//       "subcollections": { "reviews": { "<belgeId>": { "data": {...} } } }
//     }
//   }
// }
const fs = require("fs");
const path = require("path");
const { admin, db } = require("./firebase");

// Firestore'a özel tipleri (zaman damgası, konum, belge referansı) JSON'a çevrilebilir hale getirir
function serialize(value) {
  if (value instanceof admin.firestore.Timestamp) {
    return { __type: "timestamp", value: value.toDate().toISOString() };
  }
  if (value instanceof admin.firestore.GeoPoint) {
    return { __type: "geopoint", latitude: value.latitude, longitude: value.longitude };
  }
  if (value instanceof admin.firestore.DocumentReference) {
    return { __type: "reference", path: value.path };
  }
  if (Array.isArray(value)) return value.map(serialize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, serialize(v)]));
  }
  return value;
}

let documentCount = 0;

// Bir koleksiyondaki her belgeyi ve o belgenin alt koleksiyonlarını özyinelemeli olarak okur
async function exportCollection(collectionRef) {
  const result = {};
  const snapshot = await collectionRef.get();
  for (const doc of snapshot.docs) {
    documentCount++;
    const entry = { data: serialize(doc.data()) };
    const subcollections = await doc.ref.listCollections();
    if (subcollections.length > 0) {
      entry.subcollections = {};
      for (const sub of subcollections) {
        entry.subcollections[sub.id] = await exportCollection(sub);
      }
    }
    result[doc.id] = entry;
  }
  return result;
}

(async () => {
  const backup = {};
  // Kök koleksiyonlar: users, products, categories, orders ...
  for (const collection of await db.listCollections()) {
    console.log(`Okunuyor: ${collection.id}`);
    backup[collection.id] = await exportCollection(collection);
  }

  const stamp = new Date().toISOString().slice(0, 16).replace("T", "_").replace(":", "-");
  const outFile = path.join(__dirname, "backups", `shingu-backup-${stamp}.json`);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(backup, null, 2), "utf8");

  console.log(`\nYedek alındı: ${outFile}`);
  console.log(`${Object.keys(backup).length} kök koleksiyon, toplam ${documentCount} belge.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
