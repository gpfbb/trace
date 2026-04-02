# 签名文件说明

由于安全原因，实际的签名文件需要您自行生成。以下是生成签名文件的命令：

## 生成Debug签名

```bash
keytool -genkey -v -keystore trackmaster_debug.jks -keyalg RSA -keysize 2048 -validity 10000 -alias trackmaster_debug
```

- Keystore密码: trackmaster2024
- Key密码: trackmaster2024

## 生成Release签名

```bash
keytool -genkey -v -keystore trackmaster.jks -keyalg RSA -keysize 2048 -validity 10000 -alias trackmaster
```

- Keystore密码: trackmaster2024
- Key密码: trackmaster2024

## 查看SHA1指纹

```bash
# Debug签名
keytool -list -v -keystore trackmaster_debug.jks -alias trackmaster_debug

# Release签名
keytool -list -v -keystore trackmaster.jks -alias trackmaster
```

## 注意

请妥善保管您的签名文件，不要将其提交到公开的代码仓库。
