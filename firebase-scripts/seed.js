// Test verisi: 4 kategori ve 12 ürün ekler (isterlerdeki "en az 10 kayıt" maddesi için).
//
// Çalıştırma:
//   npm run seed:dry   -> hiçbir şey yazmaz, sadece eklenecekleri listeler
//   npm run seed       -> Firestore'a yazar
//
// Tekrar çalıştırmak güvenlidir: ürünler sabit kimliklerle ("seed-...") yazıldığı için
// kopya oluşmaz, aynı ürünler güncellenir. Aynı isimde kategori zaten varsa yenisi açılmaz.
const { db, dryRun } = require("./firebase");

// Uygulamadaki kategori yapısı: { name, imageUrl, createdAt }
const categories = [
  { name: "Elektronik", imageUrl: "https://picsum.photos/seed/elektronik/300/150" },
  { name: "Giyim", imageUrl: "https://picsum.photos/seed/giyim/300/150" },
  { name: "Ev & Yaşam", imageUrl: "https://picsum.photos/seed/evyasam/300/150" },
  { name: "Mevsimlik", imageUrl: "https://picsum.photos/seed/mevsimlik/300/150" },
];

// Uygulamadaki ürün yapısı (Product.fromDocument ile aynı alanlar).
// weatherTag: "sunny" | "cloudy" | "rainy" | "snowy" -> hava bu etikete uyunca weatherDiscount (%) indirimi uygulanır
const products = [
  { id: "seed-kulaklik", name: "Kablosuz Kulaklık", description: "Bluetooth 5.3, 30 saat pil ömrü, aktif gürültü engelleme.", price: 1299.9, stock: 25, categories: ["Elektronik"] },
  { id: "seed-powerbank", name: "Powerbank 20000 mAh", description: "Hızlı şarj destekli, iki USB çıkışlı taşınabilir şarj cihazı.", price: 749.5, stock: 40, categories: ["Elektronik"] },
  { id: "seed-akilli-saat", name: "Akıllı Saat", description: "Adım sayar, nabız ölçer ve bildirim desteği. Suya dayanıklı.", price: 2499, stock: 4, categories: ["Elektronik"] },
  { id: "seed-tisort", name: "Pamuklu Tişört", description: "%100 pamuk, bisiklet yaka, unisex kesim.", price: 249.9, stock: 60, categories: ["Giyim"] },
  { id: "seed-kot", name: "Slim Fit Kot Pantolon", description: "Esnek kumaş, koyu mavi yıkama.", price: 699, stock: 30, categories: ["Giyim"] },
  { id: "seed-kupa", name: "Seramik Kupa Seti (4'lü)", description: "Bulaşık makinesinde yıkanabilir, 350 ml.", price: 329.9, stock: 18, categories: ["Ev & Yaşam"] },
  { id: "seed-lamba", name: "Masa Lambası", description: "3 renk modu, dokunmatik kontrol, USB ile şarj.", price: 459, stock: 0, categories: ["Ev & Yaşam", "Elektronik"] },
  { id: "seed-semsiye", name: "Otomatik Şemsiye", description: "Tek tuşla açılıp kapanır, rüzgara dayanıklı iskelet.", price: 349.9, stock: 50, categories: ["Mevsimlik"], weatherTag: "rainy", weatherDiscount: 20 },
  { id: "seed-yagmurluk", name: "Su Geçirmez Yağmurluk", description: "Kapüşonlu, katlanabilir, hafif yağmurluk.", price: 599, stock: 22, categories: ["Mevsimlik", "Giyim"], weatherTag: "rainy", weatherDiscount: 15 },
  { id: "seed-bot", name: "Kışlık Bot", description: "Kürk astarlı, kaymaz taban.", price: 1199, stock: 12, categories: ["Mevsimlik", "Giyim"], weatherTag: "snowy", weatherDiscount: 25 },
  { id: "seed-gunes-gozlugu", name: "Güneş Gözlüğü", description: "UV400 korumalı, polarize cam.", price: 399.9, stock: 35, categories: ["Mevsimlik"], weatherTag: "sunny", weatherDiscount: 10 },
  { id: "seed-battaniye", name: "Peluş Battaniye", description: "150x200 cm, yumuşak dokulu kanepe battaniyesi.", price: 549, stock: 15, categories: ["Ev & Yaşam"], weatherTag: "cloudy", weatherDiscount: 10, onSale: false },
];

async function seedCategories() {
  for (const category of categories) {
    // Aynı isimde kategori varsa dokunma (admin elle oluşturmuş olabilir)
    const existing = await db.collection("categories").where("name", "==", category.name).limit(1).get();
    if (!existing.empty) {
      console.log(`  = Kategori zaten var: ${category.name}`);
      continue;
    }
    console.log(`  + Kategori eklenecek: ${category.name}`);
    if (!dryRun) {
      await db.collection("categories").add({ ...category, createdAt: Date.now() });
    }
  }
}

async function seedProducts() {
  // Tek seferde yazmak için batch kullanılır (en fazla 500 işlem)
  const batch = db.batch();
  const now = Date.now();
  for (const { id, ...product } of products) {
    console.log(`  + Ürün: ${product.name} (${product.price} TL, stok ${product.stock}${product.weatherTag ? `, ${product.weatherTag} %${product.weatherDiscount}` : ""})`);
    batch.set(db.collection("products").doc(id), {
      description: "",
      onSale: true,
      weatherTag: null,
      weatherDiscount: 0,
      imageUrl: `https://picsum.photos/seed/${id}/600/400`,
      createdAt: now,
      ...product,
      updatedAt: now,
    });
  }
  if (!dryRun) await batch.commit();
}

(async () => {
  console.log(dryRun ? "DENEME MODU: hiçbir şey yazılmayacak\n" : "Firestore'a yazılıyor...\n");
  console.log("Kategoriler:");
  await seedCategories();
  console.log("\nÜrünler:");
  await seedProducts();
  console.log(`\nBitti. ${categories.length} kategori, ${products.length} ürün işlendi.`);
  process.exit(0);
})().catch((e) => {
  console.error("Hata:", e.message);
  process.exit(1);
});
