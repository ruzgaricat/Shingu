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

// ---------- Anonimleştirme (--anonymize) ----------
// GitHub'a konulabilecek yedek için gerçek e-posta, isim ve adresler örnek değerlerle değiştirilir.
// Test hesapları zaten "example.com" adreslidir ve kurgusal isimlidir; onlar olduğu gibi kalır.
const anonymize = process.argv.includes("--anonymize");
const emailMap = new Map();

function anonEmail(email) {
  if (typeof email !== "string" || email === "" || email.endsWith("@example.com")) return email;
  if (!emailMap.has(email)) emailMap.set(email, `gizli-kullanici${emailMap.size + 1}@example.com`);
  return emailMap.get(email);
}

// Belge verisini özyinelemeli gezip kişisel alanları değiştirir
function anonymizeValue(value, ownerEmail) {
  if (Array.isArray(value)) return value.map((v) => anonymizeValue(v, ownerEmail));
  if (!value || typeof value !== "object") return value;

  const out = {};
  const email = value.email || value.userEmail || ownerEmail;
  const isReal = typeof email === "string" && email !== "" && !email.endsWith("@example.com");
  for (const [key, v] of Object.entries(value)) {
    if (key === "email" || key === "userEmail") out[key] = anonEmail(v);
    else if ((key === "fullName" || key === "userName") && isReal) out[key] = `Gizli Kullanıcı ${anonEmail(email).match(/\d+/)[0]}`;
    else if (key === "fullAddress") out[key] = "Örnek Mahallesi, Örnek Cadde, İzmit/Kocaeli";
    else if (key === "detail" && "fullAddress" in value) out[key] = "";
    else if ((key === "latitude" || key === "longitude") && "fullAddress" in value) out[key] = key === "latitude" ? 40.7654 : 29.9408;
    else out[key] = anonymizeValue(v, email);
  }
  return out;
}

(async () => {
  const backup = {};
  // Kök koleksiyonlar: users, products, categories, orders ...
  for (const collection of await db.listCollections()) {
    console.log(`Okunuyor: ${collection.id}`);
    backup[collection.id] = await exportCollection(collection);
  }

  const stamp = new Date().toISOString().slice(0, 16).replace("T", "_").replace(":", "-");
  if (anonymize) {
    // Anonim yedek depoya konabilir: docs/ klasörüne yazılır
    const outFile = path.join(__dirname, "..", "docs", "veritabani-yedegi-anonim.json");
    const result = { _bilgi: `Shingu Firestore yedeği (${stamp}). Kişisel veriler gizlenmiştir.`, ...anonymizeValue(backup) };
    fs.writeFileSync(outFile, JSON.stringify(result, null, 2), "utf8");
    console.log(`\nAnonim yedek alındı: ${outFile}`);
    console.log(`${Object.keys(backup).length} kök koleksiyon, toplam ${documentCount} belge, ${emailMap.size} gerçek e-posta gizlendi.`);
    process.exit(0);
  }
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
