#!/bin/sh
# Builds the PHP + MariaDB runtime from Alpine Linux packages (runs inside an alpine container
# of the target architecture). Output:
#   $OUT/dist/jniLibs/$ABI/libldmusl.so   – the musl dynamic loader (executable on Android)
#   $OUT/dist/assets/runtime.zip          – binaries, libraries, PHP extensions, MariaDB data template
set -eu
OUT="$1"
ABI="$2"
rm -rf "$OUT"
R="$OUT/rt"
mkdir -p "$R/bin" "$R/lib" "$R/php/modules" "$R/mariadb/share" "$R/mariadb/plugin" \
         "$OUT/dist/jniLibs/$ABI" "$OUT/dist/assets"

apk add --no-cache zip mariadb mariadb-client php83 \
  php83-mysqli php83-mysqlnd php83-pdo php83-pdo_mysql php83-pdo_sqlite php83-sqlite3 \
  php83-mbstring php83-session php83-ctype php83-curl php83-gd php83-zip php83-xml \
  php83-dom php83-simplexml php83-xmlreader php83-xmlwriter php83-tokenizer php83-fileinfo \
  php83-iconv php83-openssl php83-phar php83-bcmath php83-exif php83-sodium php83-calendar \
  php83-gettext php83-posix

cp -L /usr/bin/php83 "$R/bin/php"
cp -L "$(command -v mariadbd)" "$R/bin/mariadbd"
cp -L "$(command -v mariadb)" "$R/bin/mariadb"
cp -L /usr/lib/php83/modules/*.so "$R/php/modules/"

# Extension load order exactly as Alpine defines it in conf.d
for f in /etc/php83/conf.d/*.ini; do
  grep -hE '^[[:space:]]*(zend_)?extension[[:space:]]*=' "$f" || true
done > "$R/php/extensions.txt"
echo "== extensions:"; cat "$R/php/extensions.txt"

# MariaDB messages + charsets
ERRMSG=$(find /usr/share -path '*english/errmsg.sys' | head -1)
SHARE=$(dirname "$(dirname "$ERRMSG")")
cp -r "$SHARE/english" "$SHARE/charsets" "$R/mariadb/share/"

# Shared libraries needed by the binaries and extensions (transitively, via ldd)
: > /tmp/deps
for f in "$R"/bin/* "$R"/php/modules/*.so; do
  ldd "$f" 2>/dev/null | awk '$2=="=>" && $3 ~ /^\// {print $1" "$3}' >> /tmp/deps || true
done
sort -u /tmp/deps | while read -r name path; do
  case "$name" in ld-musl-*|libc.musl-*) continue ;; esac
  cp -L "$path" "$R/lib/$name"
done
echo "== libs:"; ls "$R/lib"

# musl loader -> shipped as a "native library" so Android lets us execute it
LDSO=$(ls /lib/ld-musl-*.so.1 | head -1)
cp -L "$LDSO" "$OUT/dist/jniLibs/$ABI/libldmusl.so"
(cd /lib && ls ld-musl-*.so.1 libc.musl-*.so.1 2>/dev/null) > "$R/musl-names.txt"

# Pre-initialised MariaDB data directory (root@localhost, empty password, native auth)
DD=/tmp/datadir
rm -rf "$DD"
if ! mariadb-install-db --user=root --datadir="$DD" --auth-root-authentication-method=normal \
     --innodb-log-file-size=8M --innodb-use-native-aio=0 > /tmp/install-db.log 2>&1; then
  cat /tmp/install-db.log; exit 1
fi
cp -r "$DD" "$R/datadir-template"

mariadbd --version > "$R/versions.txt"
php83 -v | head -1 >> "$R/versions.txt"
cat "$R/versions.txt"

(cd "$R" && zip -qr -9 "$OUT/dist/assets/runtime.zip" .)
du -sh "$R" "$OUT/dist/assets/runtime.zip" "$OUT/dist/jniLibs/$ABI/libldmusl.so"
