package com.example.shingu

// Ödeme ekranında gösterilen yasal metinler (örnek/şablon metinlerdir; ders projesi kapsamında hazırlanmıştır).
object LegalTexts {

    fun distanceSalesContract(itemsSummary: String, total: String, address: String) = """
        MESAFELİ SATIŞ SÖZLEŞMESİ VE ÖN BİLGİLENDİRME FORMU

        1. TARAFLAR
        Satıcı: Shingu E-Ticaret (ders projesi, gerçek bir ticari işletme değildir)
        Alıcı: Bu siparişi veren Shingu üyesi

        2. SÖZLEŞMENİN KONUSU
        Bu sözleşme, alıcının Shingu mobil uygulaması üzerinden elektronik ortamda sipariş verdiği ürünlerin satışı ve teslimi ile ilgili tarafların hak ve yükümlülüklerini, 6502 sayılı Tüketicinin Korunması Hakkında Kanun ve Mesafeli Sözleşmeler Yönetmeliği hükümleri çerçevesinde düzenler.

        3. SİPARİŞ BİLGİLERİ
        Ürünler: $itemsSummary
        Toplam tutar (vergiler ve kargo dahil): $total
        Teslimat adresi: $address
        Ödeme şekli: Kredi/Banka kartı ile online ödeme (TEST MODU, gerçek ödeme alınmaz)

        4. TESLİMAT
        Ürünler, sipariş onayından itibaren en geç 30 gün içinde alıcının belirttiği adrese teslim edilir. Şu an yalnızca Türkiye içine teslimat yapılmaktadır.

        5. CAYMA HAKKI
        Alıcı, ürünü teslim aldığı tarihten itibaren 14 gün içinde herhangi bir gerekçe göstermeden ve cezai şart ödemeden cayma hakkına sahiptir. Sipariş "Beklemede" durumundayken uygulama içinden iptal edilebilir.

        6. YETKİLİ MAHKEME
        Uyuşmazlıklarda Ticaret Bakanlığınca ilan edilen değere kadar Tüketici Hakem Heyetleri, üzerindeki değerlerde Tüketici Mahkemeleri yetkilidir.
    """.trimIndent()

    val kvkk = """
        KİŞİSEL VERİLERİN KORUNMASI AYDINLATMA METNİ

        6698 sayılı Kişisel Verilerin Korunması Kanunu (KVKK) uyarınca, Shingu olarak işlediğimiz kişisel verileriniz hakkında sizi bilgilendiririz.

        İşlenen veriler: Ad-soyad, e-posta adresi, teslimat adresi ve konumu, sipariş geçmişi, ürün yorumları.

        Ödeme bilgileri: Kart numaranızın tamamı ve güvenlik kodu (CVV) kaydedilmez. Siparişte yalnızca kart türü ve son 4 hane saklanır.

        İşleme amaçları: Üyelik işlemleri, siparişlerin alınması ve teslimatı, müşteri desteği, yasal yükümlülüklerin yerine getirilmesi.

        Aktarım: Verileriniz, hizmetin sağlanması için kullanılan altyapı sağlayıcıları (Google Firebase) dışında üçüncü kişilerle paylaşılmaz.

        Haklarınız: KVKK'nın 11. maddesi kapsamında verilerinize erişme, düzeltilmesini veya silinmesini isteme haklarına sahipsiniz. Hesabınızı Profil ekranından dondurabilir veya bilgilerinizi güncelleyebilirsiniz.
    """.trimIndent()
}
