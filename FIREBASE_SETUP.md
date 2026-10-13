# Setup Firebase (wajib, sekali saja)

Semua fitur online (login Google, nobar, chat, panggilan) memakai Firebase milik **kamu sendiri**.
Kode ada di flavor `full` (`app/src/full/java/me/tagavari/airmessage/fb/`). Build pakai varian `fullDebug`.

1. https://console.firebase.google.com -> **Add project**.
2. **Add app -> Android**. Package name:
   - build debug: `me.tagavari.airmessage.debug`
   - build release: `me.tagavari.airmessage`
   (daftarkan keduanya kalau pakai dua-duanya)
   SHA-1: jalankan `./gradlew signingReport` lalu tempel SHA-1 varian yang kamu pakai.
3. **Build -> Authentication -> Sign-in method -> Google -> Enable**.
4. **Build -> Realtime Database -> Create database** (pilih lokasi terdekat), lalu tab **Rules** ->
   tempel isi `firebase/database.rules.json` -> Publish.
5. Download `google-services.json` (download ULANG setelah database dibuat, supaya berisi `firebase_url`)
   dan taruh di `app/google-services.json`.
6. Build & run varian `fullDebug`.

## Struktur data
- `users/{uid}`            profil (nama, email, foto)
- `userChats/{uid}/{chat}` daftar percakapan tiap user
- `chats/{chatId}/messages` pesan 1:1
- `rooms/{kode}/media|state|members|chat` ruang nobar
- `calls/{id}`, `incomingCalls/{uid}` signaling panggilan (WebRTC)

## Batasan
- Telepon masuk hanya berdering saat aplikasi terbuka. Untuk berdering saat aplikasi ditutup perlu
  Cloud Function + FCM (paket Blaze).
- Panggilan memakai STUN publik Google. Di jaringan yang ketat (NAT simetris / beberapa operator)
  tambahkan server TURN di `CallActivity.createPeerConnection()`.
- Video lokal tidak diupload; tiap peserta memilih file yang sama.
