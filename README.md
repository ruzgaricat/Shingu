# Shingu — Android E-Ticaret Uygulaması

Kotlin ve Firebase ile geliştirilmiş, ev eşyası satan bir mobil e-ticaret uygulaması.
Kocaeli Üniversitesi Teknoloji Fakültesi Bilişim Sistemleri Mühendisliği, **TBL223 Mobil Uygulama Geliştirme**
dersi (2026-2027 Güz) projesidir.

- **Proje raporu:** [docs/Shingu_Proje_Raporu.docx](docs/Shingu_Proje_Raporu.docx)
- **İndirilebilir teslim paketi (kaynak kod + veritabanı yedeği + rapor):** https://drive.google.com/drive/folders/1XF5nAKfwrtWSgKBvW5A3FZUYwsy7D0c5?usp=sharing
- **Gizlilik politikası:** https://ruzgaricat.github.io/Shingu/gizlilik-politikasi.html
- **Veritabanı şeması (ERD):** [docs/ERD.md](docs/ERD.md)
- **Akış diyagramları:** [docs/AKIS_DIYAGRAMLARI.md](docs/AKIS_DIYAGRAMLARI.md)
- **Örnek veritabanı yedeği (kişisel veriler gizlenmiş):** [docs/veritabani-yedegi-anonim.json](docs/veritabani-yedegi-anonim.json)

## Özellikler

### Kullanıcı
- E-posta/şifre veya Google ile kayıt ve giriş, "Beni hatırla", şifre sıfırlama (e-posta ile)
- Profil güncelleme (ad-soyad, e-posta doğrulamalı değişim, şifre), hesabı geçici askıya alma (tekrar girişte yeniden etkinleştirme), **hesabı kalıcı silme**
- Ana sayfa: kampanya slaytı, kategori kısayolları, **ürün arama, sıralama, filtre ve fiyat aralığı**
- Ürün sayfası: çoklu görsel galerisi, stok durumu, puanlı yorumlar (kendi yorumunu silme), favoriler
- Sepet: güncel fiyat ve stok kontrolü, **kupon kodu**, kargo ücreti / bedava kargo eşiği, döviz karşılığı
- Haritadan (GPS destekli) teslimat adresi seçme; Türkiye dışı adreslere **kargo alanı kontrolü**
- Test modunda kartla ödeme (Luhn, kart türü, SKT, CVV doğrulama) ve mesafeli satış sözleşmesi onayı
- Siparişlerim: durum geçmişi, sipariş detayı, "Beklemede" siparişi iptal (stok iadesiyle)
- Kuponlarım, Ürün Yorumlarım, Adreslerim, Yardım & Destek, Geri Bildirim formu

### Yönetici
- Ürün yönetimi: ekleme, düzenleme, silme, çoklu görsel (galeriden Cloudinary'ye yükleme), stok, satışa sunma/kaldırma
- Kategori ve kampanya yönetimi (kampanya = ana sayfa banner'ı + aynı adlı kategori)
- Kullanıcı yönetimi: listeleme, ad/rol düzenleme, dondurma, silme
- Sipariş yönetimi: durum değiştirme (Beklemede → İşleme alındı → Kargoya verildi → Teslim edildi), iptal
- Kupon yönetimi, satış özeti (ciro, 7 günlük grafik, en çok satanlar), geri bildirimler
- Hava durumuna göre indirim: ürüne hava etiketi (güneşli/bulutlu/yağmurlu/karlı) ve indirim oranı verilir

## Kullanılan teknolojiler

| Alan | Teknoloji |
|---|---|
| Dil / IDE | Kotlin, Android Studio, minSdk 24 |
| Arayüz | XML layout, Material Components 3, RecyclerView, ViewPager2 |
| Kimlik doğrulama | Firebase Authentication (e-posta/şifre, Google — Credential Manager) |
| Veritabanı | Cloud Firestore (NoSQL) + güvenlik kuralları ([firestore.rules](firestore.rules)) |
| Ağ | Retrofit + Gson |
| Harita / konum | osmdroid (OpenStreetMap), Google Play Services Location (GPS) |
| Görseller | Glide, Cloudinary (görsel yükleme) |
| Test | JUnit birim testleri ([PricingAndValidationTest.kt](app/src/test/java/com/example/shingu/PricingAndValidationTest.kt)) |

### Web API'ler

| # | API | Ekran | Kullanım |
|---|---|---|---|
| 1 | [Frankfurter](https://frankfurter.dev) | Sepet | Toplam tutarın USD/EUR karşılığı |
| 2 | [Open-Meteo](https://open-meteo.com) | Ana sayfa, ürünler, sepet | Anlık hava durumu ve hava durumuna göre indirim |
| 3 | [Nominatim (OpenStreetMap)](https://nominatim.org) | Adres seçme | Koordinattan cadde/mahalle/ilçe ve ülke kodu |
| 4 | [Cloudinary](https://cloudinary.com) | Yönetici formları | Galeriden görsel yükleme |

Sensör: **GPS** (adres ekranında "Konumumu bul").

## Proje yapısı

```
app/src/main/java/com/example/shingu/
├── *Activity.kt            Giriş, kayıt, ana aktivite (alt menü)
├── *Fragment.kt            Ekranlar (ana sayfa, kategoriler, sepet, ödeme, profil, yönetici ekranları...)
├── *Repository.kt          Firestore işlemleri (sepet, sipariş, kupon, kategori, kampanya, yorum, adres, kullanıcı)
├── OrderPricing.kt         Ara toplam, kupon, kargo hesabı
├── CardValidator.kt        Kart doğrulama (test ödemesi)
├── DeliveryArea.kt         Kargo (dağıtım) alanı kontrolü
└── api/                    Retrofit servisleri (döviz, hava, adres, görsel yükleme)
firebase-scripts/           Firebase Admin SDK ile yönetim scriptleri (yedek, test verisi, test hesapları)
docs/                       Proje raporu, ERD, akış diyagramları, gizlilik politikası, anonim veritabanı yedeği
firestore.rules             Firestore güvenlik kuralları
```

## Kurulum

1. Projeyi Android Studio ile açın ve Gradle eşitlemesini (Sync) yapın.
2. Kendi Firebase projenizi kullanacaksanız `app/google-services.json` dosyasını kendi projenizinkiyle değiştirin;
   Authentication'da E-posta/Şifre ve Google girişini açın.
3. Güvenlik kurallarını yükleyin: `firebase deploy --only firestore:rules`
4. Görsel yükleme için `app/src/main/java/com/example/shingu/api/CloudinaryConfig.kt` dosyasına kendi Cloudinary
   bulut adınızı ve "unsigned" ön ayar adınızı yazın.
5. Uygulamayı bir emülatörde veya telefonda çalıştırın.

### Örnek verileri yükleme (isteğe bağlı)

Firebase Console > Proje Ayarları > Hizmet Hesapları'ndan indirilen anahtarı
`firebase-scripts/serviceAccountKey.json` olarak kaydedin (bu dosya GitHub'a **gönderilmez**), sonra:

```bash
cd firebase-scripts
npm install
npm run seed-home-categories     # 5 oda kategorisi
npm run seed-home-products       # 15 ürün
npm run seed-test-accounts       # 4 yönetici + 6 kullanıcı test hesabı
npm run seed-demo-data           # örnek siparişler, kuponlar, kampanyalar, yorumlar
npm run backup                   # veritabanı yedeği
```

Her komutun sonuna `:dry` eklenirse (ör. `npm run seed-home-products:dry`) hiçbir şey yazılmaz, sadece yapılacaklar listelenir.

## Testler

```bash
./gradlew :app:testDebugUnitTest
```

Kargo eşiği, kupon hesabı ve kuralları (kod biçimi dahil), kart doğrulama (Luhn, kart türü, SKT, CVV) ve kargo
alanı kontrolü için 17 birim testi.

## Bilinen kısıtlar

- Ödeme **test modundadır**; gerçek ödeme için sunucu tarafı (Cloud Functions, ücretli plan) gerekir.
- Firebase ücretsiz planında sunucu kodu çalışmadığı için anlık (push) bildirim gönderilemez ve yönetici başka
  bir kullanıcının giriş e-postasını değiştiremez.
- Uygulama paket adı `com.example.shingu` olduğu için Google Play'e bu haliyle yüklenemez.
