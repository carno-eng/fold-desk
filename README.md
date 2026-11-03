# Fold Desk

A personal dashboard PWA (timetable, tasks, events, people, memos) designed for foldable phones.

**No personal data lives in this repository.** The app is only a shell: all data is stored in the owner's own Google Drive and served by the owner's own Google Apps Script web app (`gas/`). Every request requires a secret token that exists only in the owner's Apps Script properties and on the owner's paired device.

- `index.html`, `sw.js`, `manifest.webmanifest`, `icons/` — the installable app
- `gas/` — Apps Script backend (copy into a new Apps Script project; see comments at the top of `Code.gs`)

<a id="setup"></a>
## 準備のしかた（はじめての人へ）

パソコンで、自分の Google アカウントを使って1回だけ行います。データは自分の Google ドライブの「Fold Desk データ」フォルダにだけ保存されます。

1. [script.google.com](https://script.google.com) を開き、「新しいプロジェクト」を作ります（名前は「Fold Desk」など）。
2. このリポジトリの `gas/Code.gs` と `gas/Google.gs` の中身を、同じ名前のファイルに貼り付けます。歯車（プロジェクトの設定）で「appsscript.json を表示」をオンにして、`gas/appsscript.json` も貼り付けます。
3. 上のメニューで **setup** を選んで「実行」し、表示に従って許可します。
4. 「デプロイ → 新しいデプロイ → 種類：ウェブアプリ」。**次のユーザーとして実行：自分**、**アクセスできるユーザー：全員** にしてデプロイします。
5. **pair** を選んで「実行」します。自分宛てに接続リンクのメールが届くので、スマホでそのメールのボタンを開きます。つながったらメールは削除してください。

スマホをなくしたときや、リンクを人に見られたときは **rotateToken** を実行すると、それまでの接続がすぐ使えなくなります。

アカウントを作らずに試したいときは、アプリの最初の画面で「お試しで使ってみる」を選べます（データはそのスマホの中だけ）。

## Third-party

- Icons: [Material Symbols](https://github.com/google/material-design-icons) (Apache License 2.0), inlined in `index.html`.
- PDF: pdf.js (Apache 2.0) and pdf-lib (MIT) in `vendor/`.
