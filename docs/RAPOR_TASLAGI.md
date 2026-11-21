# Shingu: Firebase Tabanlı, Hava Durumuna Duyarlı Mobil E-Ticaret Uygulaması

> **Taslak notu:** Bu metin, dersin rapor şablonuna aktarılmak üzere hazırlanmış bir taslaktır (hedef: 4–6 sayfa).
> `[KÖŞELİ PARANTEZ]` içindeki yerleri kendi bilgilerinizle doldurun, ekran görüntülerini ekleyin ve metni kendi
> cümlelerinizle gözden geçirin. Sunumda kodun her bölümünü açıklayabilmeniz beklendiği için metindeki her
> teknik kararı anladığınızdan emin olun.

**[Ad Soyad] — [Öğrenci No]**
Kocaeli Üniversitesi, Teknoloji Fakültesi, Bilişim Sistemleri Mühendisliği
TBL223 Mobil Uygulama Geliştirme, 2026-2027 Güz

---

## Özet

Bu çalışmada Android platformu için Kotlin dili ve Firebase altyapısı kullanılarak ev eşyası satışı yapan
bir mobil e-ticaret uygulaması (Shingu) geliştirilmiştir. Uygulama; kullanıcı ve yönetici olmak üzere iki rol,
ürün/kategori/kampanya/kupon/sipariş yönetimi, sepet, test modunda kartla ödeme, haritadan adres seçimi ve
sipariş takibi işlevlerini içermektedir. Veriler NoSQL tabanlı Cloud Firestore'da tutulmakta, veri güvenliği
sunucu tarafında çalışan Firestore güvenlik kurallarıyla sağlanmaktadır. Uygulamaya dört farklı web API entegre
edilmiştir: döviz kurları (Frankfurter), hava durumu (Open-Meteo), adres çözümleme (Nominatim) ve görsel
yükleme (Cloudinary). Projenin özgün yönü, ürün fiyatlarının anlık hava durumuna göre dinamik olarak
indirimlendirilmesidir. Sipariş verme ve iptal işlemleri, eşzamanlı isteklerde stok tutarlılığını korumak için
Firestore transaction'ları ile gerçekleştirilmiştir. Fiyat hesabı, kupon, kart doğrulama ve kargo alanı
kuralları 16 birim testiyle doğrulanmıştır.

**Anahtar kelimeler:** Android, Kotlin, Firebase, Cloud Firestore, mobil e-ticaret, REST API

---

## 1. Giriş

Mobil cihazlar, çevrim içi alışverişin en yaygın kullanıldığı kanal hâline gelmiştir. Bir mobil e-ticaret
uygulamasının yalnızca ürün listelemesi yeterli değildir; kullanıcı yönetimi, güvenli ödeme akışı, stok
tutarlılığı, sipariş takibi ve yönetim paneli gibi bileşenleri bir arada sunması gerekir.

Bu projenin amacı, mobil uygulama geliştirme yaşam döngüsünün tamamını (tasarım, geliştirme, veritabanı
yönetimi, API entegrasyonu, test ve dağıtım hazırlığı) uygulamalı olarak deneyimlemektir. Bu kapsamda:

- Rol tabanlı erişim kontrolüne (Admin / User) sahip,
- Firebase Authentication ve Cloud Firestore kullanan,
- En az iki web API'yi farklı ekranlarda kullanan,
- En az yedi ekrandan oluşan

bir e-ticaret uygulaması geliştirilmiştir. Uygulamanın konusu ev eşyalarıdır; ürünler Yatak Odası, Mutfak,
Banyo, Oturma Odası ve Balkon kategorilerinde sunulmaktadır.

---

## 2. Yöntem

### 2.1. Kullanılan teknolojiler

| Katman | Teknoloji |
|---|---|
| Geliştirme ortamı / dil | Android Studio, Kotlin (minSdk 24) |
| Arayüz | XML düzenleri, Material Components 3, RecyclerView, ViewPager2 |
| Kimlik doğrulama | Firebase Authentication (e-posta/şifre ve Google ile giriş) |
| Veritabanı | Cloud Firestore (NoSQL, belge tabanlı) |
| Ağ istekleri | Retrofit, Gson |
| Harita ve konum | osmdroid (OpenStreetMap), Google Play Services Location (GPS) |
| Görsel yükleme / gösterme | Cloudinary, Glide |
| Test | JUnit |
| Sürüm kontrolü | Git, GitHub |

### 2.2. Uygulama mimarisi

Uygulama tek bir ana aktivite (`MainActivity`) ve alt menüyle değişen fragment'lardan oluşur. Veritabanı
işlemleri ekranlardan ayrılarak **repository** nesnelerinde toplanmıştır (`CartRepository`, `OrderRepository`,
`CouponRepository`, `CategoryRepository` vb.). Böylece aynı iş kuralı (ör. sepet tutarı hesabı) sepet, ödeme
ve sipariş kaydında tek bir yerden (`OrderPricing`) kullanılmaktadır.

Ekranlar: Giriş, Kayıt, Ana Sayfa, Kategoriler, Ürün Detayı, Favoriler, Sepet, Ödeme, Profil, Üyelik
Bilgileri, Siparişlerim, Sipariş Detayı, Adreslerim, Harita ile Adres Seçimi, Ürün Yorumlarım, Kuponlarım,
Geri Bildirim ve yönetici ekranları (Ürün, Kategori, Kampanya, Kupon, Kullanıcı, Sipariş Yönetimi, Satış
Özeti).

**[Şekil 1: Ana sayfa, ürün detayı, sepet ve ödeme ekranlarının görüntüleri]**

### 2.3. Veritabanı tasarımı

Firestore'da 8 ana koleksiyon (users, products, categories, campaigns, orders, coupons, feedback) ve
kullanıcıya/ürüne bağlı alt koleksiyonlar (sepet, favoriler, adresler, yorumlar) kullanılmıştır. Siparişte
ürün, fiyat ve adresin o anki kopyası saklanarak ürün fiyatı değişse veya adres silinse bile geçmiş siparişin
bozulmaması sağlanmıştır.

**[Şekil 2: Varlık-İlişki Diyagramı — docs/ERD.md dosyasındaki diyagramın görüntüsü]**

### 2.4. Web API entegrasyonu

| API | Ekran | Amaç |
|---|---|---|
| Frankfurter | Sepet | Sepet tutarının USD/EUR karşılığı |
| Open-Meteo | Ana sayfa, ürün kartları, sepet | Anlık hava durumu ve hava durumuna göre indirim |
| Nominatim | Adres seçimi | Haritada seçilen koordinatın cadde/mahalle/ilçe ve ülke bilgisine çevrilmesi |
| Cloudinary | Yönetici formları | Telefondan seçilen görselin yüklenmesi |

Hava durumu, gereksiz istekleri önlemek için 30 dakika önbellekte tutulmakta ve tüm ekranlar aynı veriyi
kullanmaktadır. Görseller yüklenmeden önce cihazda en fazla 1600 piksele küçültülüp sıkıştırılmaktadır.

### 2.5. Güvenlik

Firestore güvenlik kuralları sunucu tarafında çalıştığı için uygulama atlatılsa bile geçerlidir:

- Kullanıcı yalnızca kendi profilini, sepetini, favorilerini ve adreslerini okuyup yazabilir; kendine yönetici
  rolü veremez, dondurulmuş hesabını kendisi açamaz.
- Sipariş yalnızca kullanıcının kendi adına, "Beklemede" durumunda, ödemesi alınmış ve Türkiye içindeki bir
  adresle oluşturulabilir. Ödeme alanında kart numarasının tamamı veya CVV bulunamaz.
- Kullanıcı ürün stoğunu yalnızca sipariş verirken azaltabilir; iptalde ise yalnızca o siparişteki adet kadar
  artırabilir.

### 2.6. Temel algoritmalar

**Sipariş verme (transaction):**

```
SİPARİŞ_VER(sepet, adres, ödeme, kuponKodu):
  TRANSACTION başlat
    her ürün için: ürünü veritabanından OKU
      ürün yoksa veya satışta değilse          -> HATA, hiçbir şey yazılmaz
      stok < sepetteki adet ise                -> HATA
      birimFiyat = HAVA_İNDİRİMİ(ürün.fiyat, ürün.havaEtiketi, ürün.indirim)
      araToplam += birimFiyat * adet
    kupon varsa: kuponu OKU, geçerli değilse   -> HATA
    tutar = araToplam - kuponİndirimi + KARGO(araToplam - kuponİndirimi)
    her ürün için: stok = stok - adet          (YAZ)
    siparişi "Beklemede" olarak YAZ
    sepeti TEMİZLE
  TRANSACTION onayla   // biri başarısız olursa hiçbiri uygulanmaz
```

**Hava durumuna göre indirim:**

```
HAVA_İNDİRİMİ(fiyat, havaEtiketi, indirimYüzdesi):
  anlıkHava = HAVA_KODUNU_ETİKETE_ÇEVİR(OpenMeteo.weather_code)
  eğer havaEtiketi == anlıkHava ise  döndür fiyat * (100 - indirimYüzdesi) / 100
  değilse                            döndür fiyat
```

**[Şekil 3: Sipariş akış diyagramı — docs/AKIS_DIYAGRAMLARI.md, Bölüm 2]**

---

## 3. Deneysel Sonuçlar

### 3.1. Birim testleri

Android/Firebase gerektirmeyen iş kuralları JUnit ile test edilmiştir (`./gradlew testDebugUnitTest`):

| Test grubu | Doğrulanan durum | Sonuç |
|---|---|---|
| Kargo | 250 TL altı ücretli, eşikte bedava, boş sepette 0 | Geçti |
| Kupon | Yüzde/tutar indirimi, tutar sepeti aşamaz, kupon sonrası eşiğin altına düşme, süre/aktiflik/en az tutar kuralları | Geçti |
| Kart doğrulama | Luhn algoritması, kart türü tespiti, SKT, türe göre CVV uzunluğu | Geçti |
| Kargo alanı | Ülke koduna göre ve koordinat sınırına göre karar | Geçti |

Toplam 16 testin tamamı başarılı olmuştur.

### 3.2. Fonksiyonel testler

Uygulama Android emülatöründe (API 37) uçtan uca denenmiştir: kayıt/giriş, ürün arama ve filtreleme, sepete
ekleme, kupon uygulama, test kartıyla ödeme, reddedilen kartla ödeme, sipariş iptali ve stok iadesi, yönetici
ile sipariş durumu değiştirme. **[Denediğiniz senaryoları ve ekran görüntülerini ekleyin]**

### 3.3. Karşılaşılan sorunlar ve çözümleri

| Sorun | Çözüm |
|---|---|
| OpenStreetMap harita karoları `com.example` paket adlı uygulamaya 403 hatası verdi | İsteklere uygulamayı tanıtan bir User-Agent eklendi |
| Açılışta hava durumu ürünlerden önce gelince fiyat filtresi çöküyordu | Fiyat aralığı kaydırıcısı iki uçla başlatıldı, filtre eksik değere karşı korundu |
| Kayıt sonrası kullanıcı belgesi yazılmadan oturum kapanabiliyordu | Belge yazımı tamamlanmadan giriş ekranına geçilmemesi sağlandı |
| Aynı anda iki sipariş stoğu eksiye düşürebilirdi | Sipariş verme transaction içine alındı, güvenlik kuralı stoğun 0'ın altına inmesini engelledi |

### 3.4. Veri

Veritabanında 15 ürün, 5 kategori ve 11 kullanıcı hesabı (5 yönetici, 6 kullanıcı) bulunmaktadır. Veritabanı
yedeği Firebase Admin SDK ile JSON olarak alınmış; kişisel verileri gizlenmiş hali GitHub deposunda
paylaşılmıştır.

---

## 4. Sonuç

Bu projede Kotlin ve Firebase kullanılarak isterlerde belirtilen tüm kullanıcı ve yönetici işlevlerini içeren
bir mobil e-ticaret uygulaması geliştirilmiştir. Proje sürecinde NoSQL veri modellemesi, sunucu tarafı
güvenlik kuralları, transaction ile veri tutarlılığı, REST API entegrasyonu, harita ve GPS kullanımı ile birim
testi konularında deneyim kazanılmıştır.

Uygulamanın kısıtları şunlardır: ödeme test modundadır ve gerçek ödeme için sunucu tarafı (Cloud Functions)
gerekmektedir; anlık bildirimler ve başka kullanıcının giriş e-postasının yönetici tarafından değiştirilmesi
Firebase'in ücretsiz planında mümkün değildir. İleride gerçek ödeme sağlayıcısı (ör. iyzico) entegrasyonu,
sipariş durumu bildirimleri ve ürün varyantları (beden, renk) eklenebilir.

**Proje kaynak kodu:** https://github.com/ruzgaricat/Shingu
**İndirilebilir paket (kod + veritabanı yedeği + rapor):** [Google Drive / OneDrive bağlantısı]

---

## Kaynakça

1. Android Developers, "Guide to app architecture," https://developer.android.com/topic/architecture
2. Kotlin Documentation, https://kotlinlang.org/docs/home.html
3. Firebase Documentation, "Cloud Firestore," https://firebase.google.com/docs/firestore
4. Firebase Documentation, "Firestore Security Rules," https://firebase.google.com/docs/firestore/security/get-started
5. Firebase Documentation, "Transactions and batched writes," https://firebase.google.com/docs/firestore/manage-data/transactions
6. Square, "Retrofit," https://square.github.io/retrofit/
7. Open-Meteo, "Weather Forecast API," https://open-meteo.com/en/docs
8. Frankfurter, "Exchange rates API," https://frankfurter.dev
9. OpenStreetMap Foundation, "Nominatim API," https://nominatim.org/release-docs/latest/api/Reverse/
10. Cloudinary, "Upload API," https://cloudinary.com/documentation/image_upload_api_reference
11. osmdroid, https://github.com/osmdroid/osmdroid
12. 6698 sayılı Kişisel Verilerin Korunması Kanunu, Resmî Gazete, 2016.
