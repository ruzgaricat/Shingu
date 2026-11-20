// Oda temalı 15 test ürünü ekler (Yatak Odası, Mutfak, Banyo, Oturma Odası, Balkon kategorilerine).
// Önce "npm run seed-home-categories" ile kategoriler eklenmiş olmalı; eksik kategori varsa script durur.
//
// Çalıştırma:
//   npm run seed-home-products:dry  -> hiçbir şey yazmaz, sadece eklenecekleri listeler
//   npm run seed-home-products      -> Firestore'a yazar
//
// Tekrar çalıştırmak güvenlidir: ürünler sabit kimliklerle ("home-...") yazıldığı için kopya oluşmaz;
// aynı ürünler scriptteki değerlere (fiyat, stok vb.) geri döner.
const { db, dryRun } = require("./firebase");

// Uygulamadaki ürün yapısı (Product.fromDocument ile aynı alanlar).
// weatherTag: "sunny" | "cloudy" | "rainy" | "snowy" -> hava bu etikete uyunca weatherDiscount (%) indirimi uygulanır
const products = [
  // ---------- Yatak Odası ----------
  { id: "home-nevresim", name: "Pamuk Saten Nevresim Takımı", price: 1299.9, stock: 20,
    categories: ["Yatak Odası"],
    description: "Çift kişilik, %100 pamuk saten nevresim takımı. Nevresim, çarşaf ve 2 yastık kılıfı içerir." },
  { id: "home-yastik", name: "Kaz Tüyü Yastık (2'li)", price: 849.9, stock: 3,
    categories: ["Yatak Odası", "Oturma Odası", "Balkon"],
    description: "50x70 cm, antialerjik kumaş, yumuşak dolgulu kaz tüyü yastık seti." },
  { id: "home-gece-lambasi", name: "Komodin Gece Lambası", price: 459.0, stock: 25,
    categories: ["Yatak Odası", "Oturma Odası"], weatherTag: "cloudy", weatherDiscount: 10,
    description: "Dokunmatik, 3 kademeli sıcak beyaz ışık. USB ile şarj edilebilir." },

  // ---------- Mutfak ----------
  { id: "home-tava-seti", name: "Granit Tava Seti (3'lü)", price: 1749.0, stock: 15,
    categories: ["Mutfak"],
    description: "20, 24 ve 28 cm granit kaplama, yapışmaz tava seti. Tüm ocak tiplerine uygundur." },
  { id: "home-fincan-seti", name: "Seramik Kahve Fincanı Seti (6'lı)", price: 399.9, stock: 40,
    categories: ["Mutfak"],
    description: "Tabaklı, 6 kişilik Türk kahvesi fincan seti. Bulaşık makinesinde yıkanabilir." },
  { id: "home-kesme-tahtasi", name: "Bambu Kesme Tahtası", price: 189.9, stock: 0,
    categories: ["Mutfak"],
    description: "40x30 cm, oluklu kenarlı doğal bambu kesme ve servis tahtası." },

  // ---------- Banyo ----------
  { id: "home-havlu-seti", name: "Pamuklu Banyo Havlusu Seti", price: 549.9, stock: 30,
    categories: ["Banyo"],
    description: "Banyo, el ve yüz havlusundan oluşan 3'lü, yüksek emicilikli pamuk havlu seti." },
  { id: "home-banyo-rafi", name: "Bambu Banyo Rafı", price: 329.0, stock: 12,
    categories: ["Banyo"],
    description: "3 katlı, neme dayanıklı bambu raf. Kolay kurulum." },
  { id: "home-paspas", name: "Kaymaz Banyo Paspası", price: 149.9, stock: 50,
    categories: ["Banyo"], weatherTag: "rainy", weatherDiscount: 15,
    description: "Kaymaz tabanlı, hızlı kuruyan yumuşak banyo paspası. 50x80 cm." },

  // ---------- Oturma Odası ----------
  { id: "home-battaniye", name: "Peluş Kanepe Battaniyesi", price: 499.0, stock: 18,
    categories: ["Oturma Odası", "Balkon"], weatherTag: "snowy", weatherDiscount: 20,
    description: "150x200 cm, yumuşak dokulu peluş battaniye. Serin akşamlar için ideal." },
  { id: "home-kirlent", name: "Dekoratif Kırlent Seti (4'lü)", price: 379.9, stock: 22,
    categories: ["Oturma Odası", "Yatak Odası", "Balkon"],
    description: "45x45 cm, fermuarlı kılıflı, dolgusuyla birlikte 4'lü dekoratif kırlent seti." },
  { id: "home-sehpa", name: "Ahşap Orta Sehpa", price: 2199.0, stock: 6,
    categories: ["Oturma Odası"],
    description: "Masif ahşap ayaklı, alt raflı modern orta sehpa. 100x50 cm." },

  // ---------- Balkon ----------
  { id: "home-balkon-seti", name: "Katlanır Balkon Masa Sandalye Seti", price: 2899.0, stock: 8,
    categories: ["Balkon"], weatherTag: "sunny", weatherDiscount: 10,
    description: "1 masa ve 2 sandalyeden oluşan, katlanabilir, dış mekâna dayanıklı set." },
  { id: "home-saksi-seti", name: "Saksı ve Çiçeklik Seti", price: 279.9, stock: 35,
    categories: ["Balkon"],
    description: "3 farklı boyutta saksı ve korkuluğa takılabilen askılı çiçeklik." },
  { id: "home-balkon-minderi", name: "Su Geçirmez Balkon Minderi", price: 349.0, stock: 14,
    categories: ["Balkon"], onSale: false,
    description: "Su itici kumaşlı, 40x40 cm balkon sandalye minderi. (Satıştan kaldırılmış ürün örneği)" },
];

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir şey yazılmayacak\n" : "Firestore'a yazılıyor...\n");

  // Ürünlerin bağlandığı kategoriler veritabanında var mı? (yoksa ürünler kategorilerde görünmez)
  const existing = new Set((await db.collection("categories").get()).docs.map((d) => d.get("name")));
  const missing = [...new Set(products.flatMap((p) => p.categories))].filter((c) => !existing.has(c));
  if (missing.length) {
    console.error(`Eksik kategori: ${missing.join(", ")}. Önce "npm run seed-home-categories" çalıştırın.`);
    process.exit(1);
  }

  // Tek seferde yazmak için batch (en fazla 500 işlem)
  const batch = db.batch();
  const now = Date.now();
  products.forEach(({ id, ...product }, index) => {
    // Her ürüne 2 görsel: ürün sayfasındaki galeriyi göstermek için; ilki kapak görselidir
    const imageUrls = [`https://picsum.photos/seed/${id}/600/400`, `https://picsum.photos/seed/${id}-2/600/400`];
    const note = [
      product.stock === 0 ? "tükendi" : product.stock <= 5 ? `son ${product.stock}` : null,
      product.onSale === false ? "satışta değil" : null,
      product.weatherTag ? `${product.weatherTag} %${product.weatherDiscount}` : null,
    ].filter(Boolean).join(", ");
    console.log(`  + ${product.name} | ${product.categories.join(", ")} | ${product.price} TL | stok ${product.stock}${note ? ` | ${note}` : ""}`);

    batch.set(db.collection("products").doc(id), {
      onSale: true,
      weatherTag: null,
      weatherDiscount: 0,
      ...product,
      imageUrls,
      imageUrl: imageUrls[0],
      createdAt: now - index * 60_000, // "En yeni" sıralaması için ürünlere farklı zamanlar
      updatedAt: now,
    });
  });
  if (!dryRun) await batch.commit();

  console.log(`\nBitti. ${products.length} ürün ${dryRun ? "eklenecek" : "eklendi"}.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
