// Sunum için örnek veri: test hesaplarıyla (seed-test-accounts.js) yapılmış gibi siparişler, yorumlar,
// geri bildirimler, favoriler ve adresler; yöneticilerin oluşturduğu kuponlar ve kampanyalar.
// Verilerin yapısı uygulamanın yazdığıyla birebir aynıdır (CartRepository.placeOrder, ReviewRepository vb.).
//
// Çalıştırma:
//   npm run seed-demo-data:dry  -> hiçbir şey yazmaz, sadece eklenecekleri listeler
//   npm run seed-demo-data      -> Firestore'a yazar
//   npm run remove-demo-data    -> bu scriptin eklediklerini siler, stokları geri ekler
//
// - Siparişler uygulamadaki gibi stok düşürür (iptal edilenler hariç); tutar, kupon ve kargo
//   uygulamadaki OrderPricing kurallarıyla hesaplanır.
// - Eklenen belgelerin kimlikleri backups/demo-data-ids.json dosyasına yazılır (silme modu bunu kullanır).
// - Veritabanında zaten sipariş varsa script durur (stoklar iki kez düşmesin diye).
const fs = require("fs");
const path = require("path");
const { admin, db, dryRun } = require("./firebase");

const FieldValue = admin.firestore.FieldValue;
const removeMode = process.argv.includes("--remove");
const IDS_FILE = path.join(__dirname, "backups", "demo-data-ids.json");

// Uygulamadaki ShopConfig ile aynı
const SHIPPING_FEE = 39.9;
const FREE_SHIPPING_THRESHOLD = 250;

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
const now = Date.now();

// ---------- Adresler (users/{uid}/addresses) — kurgusal adresler, gerçek kişilere ait değil ----------
const addresses = {
  "ayse.celik@example.com": [
    { title: "Ev", fullAddress: "Ömerağa Mh. Alemdar Cd. No:15, İzmit/Kocaeli", detail: "Daire 4", latitude: 40.7654, longitude: 29.9408 },
    { title: "İş", fullAddress: "Kozluk Mh. Şehit Ercan Cd. No:7, İzmit/Kocaeli", detail: "3. kat", latitude: 40.7720, longitude: 29.9601 },
  ],
  "burak.sahin@example.com": [
    { title: "Ev", fullAddress: "Caferağa Mh. Moda Cd. No:42, Kadıköy/İstanbul", detail: "Daire 8", latitude: 40.9861, longitude: 29.0265 },
  ],
  "deniz.arslan@example.com": [
    { title: "Ev", fullAddress: "Kızılay Mh. Atatürk Blv. No:88, Çankaya/Ankara", detail: "Daire 12", latitude: 39.9208, longitude: 32.8541 },
  ],
  "emre.koc@example.com": [
    { title: "Ev", fullAddress: "Bostanlı Mh. Cemal Gürsel Cd. No:210, Karşıyaka/İzmir", detail: "Daire 5", latitude: 38.4593, longitude: 27.0960 },
  ],
  "selin.aydin@example.com": [
    { title: "Ev", fullAddress: "Beşevler Mh. Kayalı Sk. No:3, Nilüfer/Bursa", detail: "Daire 2", latitude: 40.2140, longitude: 28.9850 },
  ],
  "oguz.yildiz@example.com": [
    { title: "Ev", fullAddress: "Konyaaltı Cd. No:61, Muratpaşa/Antalya", detail: "Daire 9", latitude: 36.8841, longitude: 30.7056 },
  ],
};

// ---------- Kuponlar (coupons/{KOD}) — kodlarda "i" harfi yok: Türkçe büyük harf dönüşümünde İ/I karışmasın ----------
const coupons = [
  { code: "MERHABA10", description: "Hoş geldin! Tüm sepette %10 indirim", type: "percent", value: 10, minCartTotal: 0, active: true, expiresAt: null },
  { code: "SAKURA50", description: "300 TL ve üzeri alışverişte 50 TL indirim", type: "amount", value: 50, minCartTotal: 300, active: true, expiresAt: now + 30 * DAY },
  { code: "KAR20", description: "Kış fırsatı: 1000 TL ve üzeri sepette %20 indirim", type: "percent", value: 20, minCartTotal: 1000, active: true, expiresAt: now + 60 * DAY },
  { code: "YAZ15", description: "Yaz kampanyası (süresi doldu)", type: "percent", value: 15, minCartTotal: 200, active: true, expiresAt: now - 10 * DAY },
];

// Ürünler adla değil kimlikle (products/{id}) seçilir; admin ürün adını değiştirse de script çalışır.

// ---------- Kampanyalar: her biri aynı adlı bir kategori açar; ürünler bu kategoriye de eklenir ----------
const campaigns = [
  { title: "Kış Fırsatları", imageFrom: "home-battaniye", createdAt: now - 2 * DAY,
    products: ["home-battaniye", "home-yastik", "home-nevresim"] },
  { title: "Mutfak Haftası", imageFrom: "home-tava-seti", createdAt: now - 1 * DAY,
    products: ["home-tava-seti", "home-fincan-seti", "home-kesme-tahtasi"] },
];

// ---------- Siparişler ----------
// ago: kaç saat önce verildi; flow: durum geçmişi [durum, siparişten kaç saat sonra]
// weather: o gün hava indirimi uygulanmış ürünler (ürün adı -> true)
const DELIVERED = [["pending", 0], ["processing", 2], ["shipped", 26], ["delivered", 72]];
const orders = [
  { email: "ayse.celik@example.com", ago: 20 * 24 + 5, flow: DELIVERED, coupon: "MERHABA10", card: ["Visa", "4242"],
    items: [["home-nevresim", 1], ["home-yastik", 1]] },
  { email: "burak.sahin@example.com", ago: 17 * 24 + 3, flow: DELIVERED, card: ["Mastercard", "4444"],
    items: [["home-tava-seti", 1], ["home-fincan-seti", 1]] },
  { email: "deniz.arslan@example.com", ago: 14 * 24 + 6, flow: DELIVERED, card: ["Troy", "0016"],
    weather: { "home-paspas": true },
    items: [["home-paspas", 2], ["home-havlu-seti", 1]] },
  { email: "emre.koc@example.com", ago: 11 * 24 + 4, flow: DELIVERED, coupon: "SAKURA50", card: ["Visa", "1881"],
    items: [["home-gece-lambasi", 2]] },
  { email: "selin.aydin@example.com", ago: 6 * 24 + 2, flow: DELIVERED, card: ["Mastercard", "5100"],
    items: [["home-kirlent", 1], ["home-battaniye", 1]] },
  { email: "oguz.yildiz@example.com", ago: 5 * 24 + 7, flow: [["pending", 0], ["cancelled", 1]], cancelledBy: "user", card: ["Visa", "4242"],
    items: [["home-sehpa", 1]] },
  { email: "ayse.celik@example.com", ago: 4 * 24 + 3, flow: [["pending", 0], ["processing", 3], ["shipped", 28]], card: ["Visa", "4242"],
    items: [["home-saksi-seti", 2]] },
  { email: "burak.sahin@example.com", ago: 3 * 24 + 6, flow: [["pending", 0], ["processing", 2], ["cancelled", 5]], cancelledBy: "admin", card: ["Mastercard", "4444"],
    items: [["home-banyo-rafi", 1]] },
  { email: "deniz.arslan@example.com", ago: 3 * 24 + 1, flow: [["pending", 0], ["processing", 4], ["shipped", 30]], card: ["Troy", "0016"],
    items: [["home-balkon-seti", 1]] },
  { email: "emre.koc@example.com", ago: 2 * 24 + 2, flow: [["pending", 0], ["processing", 5]], coupon: "MERHABA10", card: ["Visa", "1881"],
    items: [["home-fincan-seti", 1], ["home-banyo-rafi", 1]] },
  { email: "selin.aydin@example.com", ago: 26, flow: [["pending", 0], ["processing", 6]], card: ["Mastercard", "5100"],
    items: [["home-havlu-seti", 1]] },
  { email: "oguz.yildiz@example.com", ago: 3, flow: [["pending", 0]], card: ["Visa", "4242"],
    items: [["home-paspas", 1]] },
  { email: "ayse.celik@example.com", ago: 0.7, flow: [["pending", 0]], coupon: "KAR20", card: ["Visa", "4242"],
    items: [["home-sehpa", 1]] },
];

// ---------- Yorumlar: teslim alınan ürünlere, teslimattan sonra yazılmış (products/{id}/reviews/{uid} + kopyası) ----------
const reviews = [
  { email: "ayse.celik@example.com", product: "home-nevresim", rating: 5, ago: 16 * 24, comment: "Kumaşı çok yumuşak, yıkamadan sonra da formunu korudu. Renkleri fotoğraftaki gibi." },
  { email: "ayse.celik@example.com", product: "home-yastik", rating: 4, ago: 16 * 24 - 1, comment: "Çok rahat; ilk gün hafif bir koku vardı, havalandırınca geçti." },
  { email: "burak.sahin@example.com", product: "home-tava-seti", rating: 5, ago: 13 * 24, comment: "Gerçekten yapışmıyor, az yağla yemek yapabiliyorum." },
  { email: "burak.sahin@example.com", product: "home-fincan-seti", rating: 4, ago: 13 * 24 - 2, comment: "Fincanlar şık, sadece tabakları biraz küçük." },
  { email: "deniz.arslan@example.com", product: "home-paspas", rating: 5, ago: 10 * 24, comment: "Gerçekten kaymıyor, suyu da hızlı çekiyor." },
  { email: "deniz.arslan@example.com", product: "home-havlu-seti", rating: 3, ago: 10 * 24 - 1, comment: "Kalitesi iyi ama ilk yıkamada biraz tüy döktü." },
  { email: "emre.koc@example.com", product: "home-gece-lambasi", rating: 5, ago: 7 * 24, comment: "Işığı göz yormuyor, yatak odasına çok yakıştı." },
  { email: "selin.aydin@example.com", product: "home-kirlent", rating: 4, ago: 2 * 24, comment: "Renkleri canlı, kılıflarının fermuarlı olması büyük avantaj." },
  { email: "selin.aydin@example.com", product: "home-battaniye", rating: 5, ago: 2 * 24 - 1, comment: "Kış akşamları için birebir, çok yumuşak ♡" },
];

// ---------- Geri bildirimler (feedback) ----------
const feedback = [
  { email: "ayse.celik@example.com", subject: "Öneri", ago: 15 * 24, read: true, message: "Ürün sayfalarına ölçü tablosu eklenirse çok iyi olur." },
  { email: "burak.sahin@example.com", subject: "Sipariş / teslimat", ago: 3 * 24, read: false, message: "Banyo rafı siparişim iptal edildi ama nedenini göremedim, bilgi verebilir misiniz?" },
  { email: "emre.koc@example.com", subject: "Hata bildirimi", ago: 30, read: false, message: "Haritadan adres seçerken konumum ilk denemede gelmedi, ikincisinde geldi." },
  { email: "selin.aydin@example.com", subject: "Diğer", ago: 5, read: true, message: "Uygulamanın yeni tasarımı çok tatlı olmuş, teşekkürler!" },
];

// ---------- Favoriler (users/{uid}/favorites/{productId}) ----------
const favorites = {
  "ayse.celik@example.com": ["home-battaniye", "home-sehpa", "home-saksi-seti"],
  "burak.sahin@example.com": ["home-balkon-seti", "home-tava-seti"],
  "deniz.arslan@example.com": ["home-gece-lambasi"],
  "emre.koc@example.com": ["home-kirlent", "home-nevresim"],
  "selin.aydin@example.com": ["home-havlu-seti", "home-fincan-seti"],
  "oguz.yildiz@example.com": ["home-balkon-seti"],
};

const tl = (v) => v.toFixed(2).replace(".", ",") + " TL";

function fail(message) {
  console.error("HATA: " + message);
  process.exit(1);
}

// Kupon indirimi + kargo: uygulamadaki Coupon.discountFor ve OrderPricing.calculate ile aynı
function price(subtotal, coupon) {
  let discount = 0;
  if (coupon) {
    if (coupon.active === false) fail(`${coupon.code} aktif değil`);
    if (subtotal < coupon.minCartTotal) fail(`${coupon.code} için sepet tutarı yetersiz (${tl(subtotal)})`);
    discount = coupon.type === "percent" ? (subtotal * coupon.value) / 100 : Math.min(coupon.value, subtotal);
  }
  const afterDiscount = subtotal - discount;
  const shipping = subtotal === 0 || afterDiscount >= FREE_SHIPPING_THRESHOLD ? 0 : SHIPPING_FEE;
  return { subtotal, discount, shipping, total: afterDiscount + shipping };
}

async function loadLookups() {
  const [usersSnap, productsSnap] = await Promise.all([db.collection("users").get(), db.collection("products").get()]);
  const users = {};
  usersSnap.docs.forEach((d) => (users[d.get("email")] = { uid: d.id, fullName: d.get("fullName") }));
  const products = {};
  productsSnap.docs.forEach((d) => (products[d.id] = { id: d.id, ref: d.ref, ...d.data() }));
  return { users, products };
}

async function seed() {
  console.log(dryRun ? "DENEME MODU: hiçbir şey yazılmayacak\n" : "Firestore'a yazılıyor...\n");

  const existingOrders = await db.collection("orders").limit(1).get();
  if (!existingOrders.empty) fail("Veritabanında zaten sipariş var. Stoklar iki kez düşmesin diye durduruldu.");
  for (const c of coupons) {
    if ((await db.collection("coupons").doc(c.code).get()).exists) fail(`${c.code} kuponu zaten var.`);
  }

  const { users, products } = await loadLookups();
  const user = (email) => users[email] || fail(`Test hesabı bulunamadı: ${email} (önce seed-test-accounts çalıştırılmalı)`);
  const product = (id) => products[id] || fail(`Ürün bulunamadı: ${id}`);
  const couponByCode = Object.fromEntries(coupons.map((c) => [c.code, c]));

  const batch = db.batch();
  const ids = { orders: [], feedback: [], campaigns: [], categories: [], coupons: [], reviews: [], favorites: [], addresses: [], stock: {}, productCategories: [] };

  // Adresler
  const addressByEmail = {};
  console.log("ADRESLER");
  for (const [email, list] of Object.entries(addresses)) {
    const u = user(email);
    list.forEach((a, i) => {
      const data = { ...a, countryCode: "tr" };
      const ref = db.collection("users").doc(u.uid).collection("addresses").doc();
      batch.set(ref, { ...data, createdAt: now - (25 - i) * DAY });
      ids.addresses.push(ref.path);
      if (i === 0) addressByEmail[email] = data;
      console.log(`  ${u.fullName}: ${a.title} - ${a.fullAddress}`);
    });
  }

  // Kuponlar
  console.log("\nKUPONLAR");
  for (const c of coupons) {
    batch.set(db.collection("coupons").doc(c.code), { ...c, updatedAt: now - 22 * DAY });
    ids.coupons.push(c.code);
    const label = c.type === "percent" ? `%${c.value}` : tl(c.value);
    console.log(`  ${c.code}: ${label}, en az ${tl(c.minCartTotal)}${c.expiresAt && c.expiresAt < now ? " (SÜRESİ DOLMUŞ)" : ""}`);
  }

  // Kampanyalar + kategorileri; ürünlerin kategori listesine kampanya adı eklenir
  console.log("\nKAMPANYALAR");
  const categoryNames = new Set((await db.collection("categories").get()).docs.map((d) => d.get("name")));
  for (const c of campaigns) {
    if (categoryNames.has(c.title)) fail(`"${c.title}" adında kategori zaten var.`);
    const imageUrl = (product(c.imageFrom).imageUrls || [])[0] || "";
    const categoryRef = db.collection("categories").doc();
    const campaignRef = db.collection("campaigns").doc();
    batch.set(categoryRef, { name: c.title, imageUrl, campaignId: campaignRef.id, createdAt: c.createdAt });
    batch.set(campaignRef, { title: c.title, imageUrl, categoryId: categoryRef.id, categoryName: c.title, createdAt: c.createdAt });
    ids.campaigns.push(campaignRef.id);
    ids.categories.push(categoryRef.id);
    for (const name of c.products) {
      const p = product(name);
      batch.update(p.ref, { categories: FieldValue.arrayUnion(c.title) });
      ids.productCategories.push({ productId: p.id, category: c.title });
    }
    console.log(`  ${c.title} -> ${c.products.map((id) => product(id).name).join(", ")}`);
  }

  // Siparişler
  console.log("\nSİPARİŞLER");
  const stockChange = {};
  for (const o of orders) {
    const u = user(o.email);
    const createdAt = Math.round(now - o.ago * HOUR);
    const orderItems = [];
    const itemQuantities = {};
    let subtotal = 0;
    for (const [productId, quantity] of o.items) {
      const p = product(productId);
      if (p.onSale === false) fail(`${p.name} satışta değil`);
      const discountApplied = !!(o.weather && o.weather[productId] && p.weatherTag && p.weatherDiscount > 0);
      const unitPrice = discountApplied ? (p.price * (100 - p.weatherDiscount)) / 100 : p.price;
      orderItems.push({
        productId: p.id,
        name: p.name,
        imageUrl: (p.imageUrls || [])[0] || "",
        price: unitPrice,
        originalPrice: p.price,
        weatherDiscount: discountApplied ? p.weatherDiscount : 0,
        quantity,
      });
      itemQuantities[p.id] = quantity;
      subtotal += unitPrice * quantity;
    }
    const coupon = o.coupon ? couponByCode[o.coupon] : null;
    const pricing = price(subtotal, coupon);
    const history = o.flow.map(([status, after]) => ({ status, at: Math.min(createdAt + after * HOUR, now) }));
    const last = history[history.length - 1];
    const status = last.status;

    // İptal edilmeyen siparişler stoğu düşürür (iptalde stok geri eklendiği için net değişim yok)
    if (status !== "cancelled") {
      for (const [productId, q] of Object.entries(itemQuantities)) stockChange[productId] = (stockChange[productId] || 0) + q;
    }

    const ref = db.collection("orders").doc();
    const data = {
      userId: u.uid,
      userEmail: o.email,
      items: orderItems,
      itemQuantities,
      subtotal: pricing.subtotal,
      couponCode: coupon ? coupon.code : null,
      couponDiscount: pricing.discount,
      shippingFee: pricing.shipping,
      totalPrice: pricing.total,
      address: addressByEmail[o.email],
      payment: { method: "card", cardBrand: o.card[0], cardLast4: o.card[1], status: "paid", testMode: true, paidAt: createdAt },
      agreementsAcceptedAt: createdAt,
      status,
      statusHistory: history,
      createdAt,
    };
    if (history.length > 1) data.statusUpdatedAt = last.at;
    if (o.cancelledBy) data.cancelledBy = o.cancelledBy;
    batch.set(ref, data);
    ids.orders.push(ref.id);

    const date = new Date(createdAt).toLocaleString("tr-TR", { dateStyle: "short", timeStyle: "short" });
    const what = o.items.map(([id, q]) => `${q}x ${product(id).name}`).join(", ");
    console.log(`  #${ref.id.slice(0, 6).toUpperCase()} ${date} ${u.fullName} [${status}${o.cancelledBy ? "/" + o.cancelledBy : ""}] ${what} = ${tl(pricing.total)}${coupon ? " (" + coupon.code + ")" : ""}`);
  }

  // Stok düşümü
  console.log("\nSTOK DEĞİŞİMİ");
  for (const [productId, q] of Object.entries(stockChange)) {
    const p = products[productId];
    if (p.stock != null) {
      if (p.stock < q) fail(`${p.name} için stok yetersiz (${p.stock} < ${q})`);
      batch.update(p.ref, { stock: FieldValue.increment(-q) });
      ids.stock[productId] = q;
      console.log(`  ${p.name}: ${p.stock} -> ${p.stock - q}`);
    }
  }

  // Yorumlar (ürün altında + kullanıcının kopyası)
  console.log("\nYORUMLAR");
  for (const r of reviews) {
    const u = user(r.email);
    const p = product(r.product);
    const data = { userId: u.uid, userName: u.fullName, productId: p.id, rating: r.rating, comment: r.comment, createdAt: Math.round(now - r.ago * HOUR) };
    const productReview = db.collection("products").doc(p.id).collection("reviews").doc(u.uid);
    const userReview = db.collection("users").doc(u.uid).collection("reviews").doc(p.id);
    batch.set(productReview, data);
    batch.set(userReview, data);
    ids.reviews.push(productReview.path, userReview.path);
    console.log(`  ${"★".repeat(r.rating)}${"☆".repeat(5 - r.rating)} ${u.fullName} -> ${p.name}`);
  }

  // Geri bildirimler
  console.log("\nGERİ BİLDİRİMLER");
  for (const f of feedback) {
    const u = user(f.email);
    const ref = db.collection("feedback").doc();
    batch.set(ref, { userId: u.uid, userEmail: f.email, subject: f.subject, message: f.message, read: f.read, createdAt: Math.round(now - f.ago * HOUR) });
    ids.feedback.push(ref.id);
    console.log(`  [${f.subject}] ${u.fullName}${f.read ? "" : " (okunmadı)"}`);
  }

  // Favoriler
  console.log("\nFAVORİLER");
  for (const [email, productIds] of Object.entries(favorites)) {
    const u = user(email);
    productIds.forEach((productId, i) => {
      const p = product(productId);
      const ref = db.collection("users").doc(u.uid).collection("favorites").doc(p.id);
      batch.set(ref, { productId: p.id, addedAt: now - (10 - i) * DAY });
      ids.favorites.push(ref.path);
    });
    console.log(`  ${u.fullName}: ${productIds.map((id) => product(id).name).join(", ")}`);
  }

  if (!dryRun) {
    await batch.commit();
    fs.mkdirSync(path.dirname(IDS_FILE), { recursive: true });
    fs.writeFileSync(IDS_FILE, JSON.stringify(ids, null, 2));
  }
  console.log(
    `\nBitti. ${orders.length} sipariş, ${reviews.length} yorum, ${feedback.length} geri bildirim, ${coupons.length} kupon, ` +
      `${campaigns.length} kampanya ${dryRun ? "eklenecek" : "eklendi"}.`
  );
}

// Bu scriptin eklediklerini siler ve düşülen stokları geri ekler
async function remove() {
  if (!fs.existsSync(IDS_FILE)) fail(`${IDS_FILE} bulunamadı; silinecek örnek veri kaydı yok.`);
  const ids = JSON.parse(fs.readFileSync(IDS_FILE, "utf8"));
  console.log(dryRun ? "DENEME MODU: hiçbir şey silinmeyecek\n" : "Örnek veriler siliniyor...\n");

  const batch = db.batch();
  ids.orders.forEach((id) => batch.delete(db.collection("orders").doc(id)));
  ids.feedback.forEach((id) => batch.delete(db.collection("feedback").doc(id)));
  ids.campaigns.forEach((id) => batch.delete(db.collection("campaigns").doc(id)));
  ids.categories.forEach((id) => batch.delete(db.collection("categories").doc(id)));
  ids.coupons.forEach((code) => batch.delete(db.collection("coupons").doc(code)));
  [...ids.reviews, ...ids.favorites, ...ids.addresses].forEach((p) => batch.delete(db.doc(p)));
  ids.productCategories.forEach(({ productId, category }) =>
    batch.update(db.collection("products").doc(productId), { categories: FieldValue.arrayRemove(category) })
  );
  Object.entries(ids.stock).forEach(([productId, q]) =>
    batch.update(db.collection("products").doc(productId), { stock: FieldValue.increment(q) })
  );
  console.log(
    `${ids.orders.length} sipariş, ${ids.reviews.length / 2} yorum, ${ids.feedback.length} geri bildirim, ${ids.coupons.length} kupon, ` +
      `${ids.campaigns.length} kampanya, ${ids.favorites.length} favori, ${ids.addresses.length} adres silinecek; stoklar geri eklenecek.`
  );
  if (!dryRun) {
    await batch.commit();
    fs.unlinkSync(IDS_FILE);
    console.log("Silindi.");
  }
}

(removeMode ? remove() : seed())
  .then(() => process.exit(0))
  .catch((e) => fail(e.message));
