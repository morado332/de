#!/bin/bash
# Tests the runtime the way the Android app uses it, on a NON-musl Linux (Debian), as a
# non-root user, with no exec bits on the extracted files – only the loader is executable.
set -euo pipefail
OUT="$1"
ROUTER="$2"
if [ "$(id -u)" = 0 ]; then
  apt-get update -qq && apt-get install -y -qq --no-install-recommends unzip > /dev/null
  T=/tmp/pvtest; rm -rf $T; mkdir -p $T
  chown -R 65534:65534 $T
  exec setpriv --reuid=65534 --regid=65534 --clear-groups env HOME=$T T=$T bash "$0" "$@"
fi
mkdir -p $T/nlib $T/run $T/tmp $T/logs $T/data $T/www/sub
cp "$OUT"/dist/jniLibs/*/libldmusl.so $T/nlib/ && chmod 755 $T/nlib/libldmusl.so
unzip -q "$OUT/dist/assets/runtime.zip" -d $T/rt
find $T/rt -type f -exec chmod 600 {} +
for n in $(cat $T/rt/musl-names.txt); do ln -sf $T/nlib/libldmusl.so $T/rt/lib/$n; done
L=("$T/nlib/libldmusl.so" --library-path "$T/rt/lib")
SOCK=$T/run/mysqld.sock

echo "== php -v"; "${L[@]}" $T/rt/bin/php -n -v
{ echo "extension_dir=\"$T/rt/php/modules\""; cat $T/rt/php/extensions.txt
  echo "mysqli.default_socket=\"$SOCK\""; echo "pdo_mysql.default_socket=\"$SOCK\""
  echo "session.save_path=\"$T/tmp\""; echo "display_errors=On"; echo "variables_order=EGPCS"; } > $T/run/php.ini
export PHP_INI_SCAN_DIR=
PHP=("${L[@]}" $T/rt/bin/php -c $T/run/php.ini)
"${PHP[@]}" -m > $T/mods.txt 2>&1; cat $T/mods.txt
if grep -qiE 'warning|unable' $T/mods.txt; then echo "Extension load problem"; exit 1; fi
for m in mysqli pdo_mysql pdo_sqlite mbstring gd zip curl session openssl; do grep -qx "$m" $T/mods.txt || { echo "missing $m"; exit 1; }; done

echo "== mariadb"
cp -r $T/rt/datadir-template $T/data/mysql
"${L[@]}" $T/rt/bin/mariadbd --no-defaults --basedir=$T/rt/mariadb --datadir=$T/data/mysql \
  --plugin-dir=$T/rt/mariadb/plugin --lc-messages-dir=$T/rt/mariadb/share --lc-messages=en_US \
  --character-sets-dir=$T/rt/mariadb/share/charsets --socket=$SOCK --port=3306 --bind-address=127.0.0.1 \
  --skip-name-resolve --pid-file=$T/run/mysqld.pid --tmpdir=$T/tmp --log-error=$T/logs/mysql.err \
  --innodb-use-native-aio=0 --innodb-flush-method=fsync --innodb-buffer-pool-size=64M \
  --innodb-log-file-size=8M --performance-schema=OFF --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci --max-connections=60 --default-time-zone=+00:00 \
  --sql-mode=NO_ENGINE_SUBSTITUTION --max-allowed-packet=64M > $T/logs/mysql-out.log 2>&1 &
DBPID=$!
for i in $(seq 1 240); do [ -S $SOCK ] && break; kill -0 $DBPID 2>/dev/null || break; sleep 1; done
if [ ! -S $SOCK ]; then cat $T/logs/mysql.err $T/logs/mysql-out.log || true; exit 1; fi
MY=("${L[@]}" $T/rt/bin/mariadb --no-defaults --socket=$SOCK --default-character-set=utf8mb4 --character-sets-dir=$T/rt/mariadb/share/charsets --batch)
MYSQL_PWD= "${MY[@]}" -uroot <<'SQL'
SELECT VERSION();
CREATE USER IF NOT EXISTS 'pvadmin'@'localhost' IDENTIFIED BY 'Adm1n';
ALTER USER 'pvadmin'@'localhost' IDENTIFIED BY 'Adm1n';
GRANT ALL PRIVILEGES ON *.* TO 'pvadmin'@'localhost' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL
MYSQL_PWD=Adm1n "${MY[@]}" -upvadmin <<'SQL'
CREATE DATABASE IF NOT EXISTS `shop_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shop'@'localhost' IDENTIFIED BY 'p@ss\'w';
ALTER USER 'shop'@'localhost' IDENTIFIED BY 'p@ss\'w';
GRANT ALL PRIVILEGES ON `shop_db`.* TO 'shop'@'localhost';
CREATE USER IF NOT EXISTS 'shop'@'127.0.0.1' IDENTIFIED BY 'p@ss\'w';
ALTER USER 'shop'@'127.0.0.1' IDENTIFIED BY 'p@ss\'w';
GRANT ALL PRIVILEGES ON `shop_db`.* TO 'shop'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
printf 'CREATE TABLE items (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\nINSERT INTO items (name) VALUES ("مرحبا"),("hello");\n' > $T/dump.sql
MYSQL_PWD=Adm1n "${MY[@]}" -upvadmin shop_db < $T/dump.sql

cat > $T/www/index.php <<'PHP'
<?php
$a = new mysqli('localhost', 'shop', "p@ss'w", 'shop_db');
$b = new mysqli('127.0.0.1', 'shop', "p@ss'w", 'shop_db');
$pdo = new PDO('mysql:host=localhost;dbname=shop_db;charset=utf8mb4', 'shop', "p@ss'w");
$c = $pdo->query('SELECT COUNT(*) FROM items')->fetchColumn();
$r = $a->query('SELECT name FROM items ORDER BY id LIMIT 1')->fetch_row()[0];
session_start(); $_SESSION['x'] = 1;
echo "OK rows=$c first=$r route=" . $_SERVER['REQUEST_URI'];
PHP
echo '<?php echo "SUB";' > $T/www/sub/index.php
echo "== php cli db test"; (cd $T/www && "${PHP[@]}" index.php); echo

"${PHP[@]}" -S 127.0.0.1:8080 -t $T/www "$ROUTER" > $T/logs/php.log 2>&1 &
WEBPID=$!
for i in $(seq 1 60); do "${PHP[@]}" -r 'exit(@fsockopen("127.0.0.1",8080)?0:1);' && break; sleep 1; done
get() { "${PHP[@]}" -r 'echo file_get_contents("http://127.0.0.1:8080".$argv[1]);' "$1"; }
R1=$(get /); echo "$R1"; [[ "$R1" == "OK rows=2 first=مرحبا route=/" ]]
R2=$(get /pretty/url?x=1); echo "$R2"; [[ "$R2" == *"route=/pretty/url?x=1"* ]]
R3=$(get /sub/); echo "$R3"; [[ "$R3" == "SUB" ]]
kill $WEBPID
kill -TERM $DBPID; wait $DBPID || true
tail -3 $T/logs/mysql.err
echo "ALL RUNTIME TESTS PASSED"
