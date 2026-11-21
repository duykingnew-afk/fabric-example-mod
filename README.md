# Pocket FSR

Mod Fabric cho **Minecraft 1.21.1** giúp tăng FPS trên điện thoại / máy tính bảng (iOS, Android) bằng cách
**render thế giới 3D ở độ phân giải thấp hơn rồi nâng lên bằng FSR 1.0**, còn giao diện (thanh hotbar, chat, menu)
vẫn vẽ ở độ phân giải gốc nên chữ vẫn nét.

> A Fabric mod for Minecraft 1.21.1 that draws the 3D world at a lower resolution and upscales it with an
> FSR 1.0 style filter (EASU + RCAS) written in plain OpenGL. The GUI stays at native resolution.
> No native libraries and no Vulkan, so it can run through the GL translation layers used by mobile launchers.

**Trạng thái: thử nghiệm.** Mod được viết mà không chạy thử được game trong môi trường phát triển, nên chưa
kiểm chứng trên máy thật. Nếu bị lỗi, gửi file `logs/latest.log` ở trong issue.

## Cài đặt

1. Cài Fabric Loader cho 1.21.1 và [Fabric API](https://modrinth.com/mod/fabric-api).
2. Tải file `.jar` ở mục **Releases** và bỏ vào thư mục `mods`.

## Cách dùng

- Trong game, vào **Tạm dừng (Esc)** hoặc **Cài đặt video**: có nút **Pocket FSR** ở góc trên bên trái.
- Phím tắt: `F7` bật/tắt, `F8` mở màn hình cài đặt (đổi được trong Điều khiển).
- File cấu hình: `config/pocketfsr.json`.

| Tùy chọn | Ý nghĩa |
|---|---|
| Tỉ lệ render thế giới | 25% – 100%. Gợi ý: 67% (Quality), 59% (Balanced), 50% (Performance), 33% (Ultra Performance) |
| Phương pháp | `FSR 1.0` (đẹp hơn) hoặc `Bilinear` (nhanh nhất) |
| Độ sắc nét | RCAS, 0% = tắt |

## Giới hạn đã biết

- Đồ họa **Fabulous** chưa hỗ trợ: mod tự tắt, hãy dùng Fast hoặc Fancy.
- **Iris** / shader pack: không tương thích, mod tự tắt.
- Viền sáng của thực thể phát sáng / chế độ spectator có thể bị lệch khi đang nâng độ phân giải.
- FSR 1.0 chỉ là lọc không gian (không dùng dữ liệu các frame trước), nên ở tỉ lệ rất thấp ảnh sẽ mềm.
- Chỉ dành cho 1.21.1.

## Cách hoạt động

```
GameRenderer.renderLevel  (mixin)
   ├─ HEAD:   thay "main render target" bằng một target nhỏ hơn → thế giới được vẽ ở độ phân giải thấp
   └─ RETURN: trả target thật về, chạy shader FSR (EASU → RCAS) từ ảnh nhỏ lên target thật
GUI được vẽ sau đó ở độ phân giải gốc
```

Shader nằm ở `src/client/resources/assets/pocketfsr/shaders/core/` (GLSL 1.50, không dùng `textureGather`
nên chạy được trên các lớp dịch GL của launcher di động).

## Build

```sh
./gradlew build      # cần JDK 21 trở lên; file jar nằm ở build/libs/
```

GitHub Actions (`.github/workflows/build.yml`) tự build mỗi lần push. Để tạo bản phát hành:

```sh
git tag v0.1.0
git push origin v0.1.0     # workflow release.yml sẽ tạo Release kèm file jar
```

## Tạo mod Fabric trên GitHub (tóm tắt)

1. Vào <https://github.com/FabricMC/fabric-example-mod> → **Use this template** → tạo repo của bạn.
   Chọn nhánh đúng phiên bản Minecraft (ví dụ `1.21.1`); thông số phiên bản tra ở <https://fabricmc.net/develop>.
2. Đổi `modid`, tên gói Java, `fabric.mod.json`, `gradle.properties`, `settings.gradle` cho khớp mod của bạn.
3. Viết code trong `src/main/java` (chung) và `src/client/java` (chỉ client); mixin khai báo trong file `*.mixins.json`.
4. Push lên GitHub: Actions sẽ tự chạy `./gradlew build` và lưu file jar ở mục Artifacts.
5. Gắn tag `vX.Y.Z` để tạo Release có sẵn file jar cho người khác tải.

## Giấy phép và ghi công

- Mã nguồn của dự án: **GPL-3.0-or-later** (xem `LICENSE`).
- Ý tưởng thay render target trước khi vẽ thế giới và upscale trước khi vẽ GUI dựa theo mod
  [Super Resolution](https://github.com/IReallyWantToSleep/superresolution) của 187J3X1-114514 (GPL-3.0). Pocket FSR là bản viết lại
  nhỏ gọn, không dùng thư viện native, nhắm tới thiết bị di động.
- Thuật toán FSR 1.0 (EASU, RCAS) của **AMD FidelityFX**, giấy phép MIT: xem `LICENSE-FSR.txt`.
- Minecraft là sản phẩm của Mojang Studios / Microsoft. Dự án này không liên kết với họ.
