# AstroLens

Telefon jako automatický teleskop. Živý obraz z fotoaparátu s překrytou mapou oblohy
(hvězdy, souhvězdí, Mléčná dráha, mlhoviny, galaxie, hvězdokupy, planety, Slunce, Měsíc),
plně ruční fotoaparát (ISO, čas až desítky sekund, ostření, zoom, RAW/DNG, série) a režimy
pro oblohu, Měsíc a planety. Vše funguje bez internetu.

## Jak získat APK

APK se staví na GitHubu, na počítači nepotřebujete Android Studio.

1. Na https://github.com vytvořte nový prázdný repozitář (např. `AstroLens`).
2. Nahrajte do něj celý obsah této složky (tlačítko *Add file → Upload files*, přetáhněte soubory včetně složky `.github`; nebo přes `git push`).
3. Otevřete záložku **Actions**. Workflow **Build APK** se spustí sám (první build trvá asi 5–8 minut). Můžete ho spustit i ručně přes *Run workflow*.
4. Po dokončení otevřete běh, dole v části **Artifacts** stáhněte `AstroLens-debug-apk` a rozbalte ZIP.
5. Soubor `app-debug.apk` přeneste do telefonu, povolte instalaci z neznámých zdrojů a nainstalujte ho.

Při prvním spuštění povolte fotoaparát a polohu.

## Ovládání

- **Klepnutí na objekt** v obraze: karta s informacemi, tlačítko *Navést* ukáže šipku, kterým směrem natočit telefon.
- **Dva prsty**: zoom.
- **Expozice**: ISO, čas závěrky, ostření (nekonečno), zoom, vyvážení bílé, JPEG/RAW, samospoušť, série snímků.
- **Režimy**: Hvězdná obloha, Mléčná dráha, Souhvězdí, Hluboký vesmír, Měsíc, Planety, Hvězdné stopy, Meteory, Polární záře, Soumrak, Automatika.
- **Obloha**: zapínání vrstev, jas, popisky, doladění zorného pole.
- **Hledat**: vyhledávání objektů a seznam „Právě teď na obloze“.
- **Víc**: kalibrace směru, zdroj natočení, poloha, posun času, noční (červený) režim, výběr objektivu.

Snímky se ukládají do `Obrázky/AstroLens`. Volitelně se ukládá i kopie s popisky objektů.

## Tipy

- Kompas telefonu bývá nepřesný o několik stupňů. Zaměřte jasný objekt (Měsíc, Jupiter, Venuše, Sírius) do středu, vyberte ho a použijte *Víc → Kalibrovat*.
- Pro dlouhé expozice použijte stativ a samospoušť 2 s.
- Maximální čas závěrky určuje telefon (obvykle 10–30 s). Aplikace nabídne jen to, co fotoaparát opravdu umí.

## Struktura

- `app/src/main/java/cz/astrolens/astro` – výpočty polohy Slunce, Měsíce a planet, katalog, převody souřadnic
- `.../camera` – Camera2, ruční ovládání, RAW, série, režimy
- `.../sensor` – natočení telefonu a poloha
- `.../ui` – vykreslování oblohy, panely, karty objektů
- `tools/build_catalog.py` – znovu vytvoří katalogová data ze zdrojů (viz NOTICE)

## Licence dat

Viz `NOTICE`.
