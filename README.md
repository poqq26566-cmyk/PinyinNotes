# 拼音笔记 PinyinNotes

一个极简安卓应用：新建带名称的条目，按名称拼音首字母自动分组为 A-Z 列表（类似通讯录），
点击某个名称即可进入全屏空白编辑页记录内容，内容自动保存在本地。

## 功能
- 右下角"+"新建名称
- 主界面按拼音首字母自动分组显示（A、B、C...Z，非中文/非字母开头归入 #）
- 点击名称进入全屏 EditText，边输入边自动保存
- 数据保存在本地 JSON 文件，无需网络、无需数据库依赖
- 拼音转换使用 Android 系统自带 ICU 库（Han-Latin），无需第三方拼音库

## 在 GitHub 上编译

### 方式一：GitHub Actions 自动编译（推荐）
1. 把整个项目上传到你的 GitHub 仓库根目录
2. 仓库里已包含 `.github/workflows/android-build.yml`
3. push 到 `main` 分支后会自动触发编译
4. 编译完成后，在 Actions 运行记录的 "Artifacts" 里下载 `app-debug`，
   解压得到 `app-debug.apk`，安装到手机即可

### 方式二：用 Android Studio 本地打开
1. 用 Android Studio 打开本项目文件夹
2. 首次打开会自动生成 gradlew 包装脚本并同步依赖（需要联网）
3. 点击运行按钮即可安装到模拟器/真机

## 项目结构
```
PinyinNotes/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/pinyinnotes/
│       │   ├── MainActivity.kt      主列表页
│       │   ├── EditActivity.kt      全屏编辑页
│       │   ├── Note.kt              数据模型
│       │   ├── NoteRepository.kt    本地 JSON 存取
│       │   ├── NoteAdapter.kt       分组列表适配器
│       │   └── PinyinUtils.kt       拼音首字母转换
│       └── res/layout, res/values
├── build.gradle
├── settings.gradle
└── .github/workflows/android-build.yml
```

## 最低系统要求
minSdk 24（Android 7.0 及以上），因拼音转换依赖系统 ICU 库。
