<?php
// Router for the PHP built-in server used by the preview app.
// - /__adminer       -> database manager (Adminer)
// - existing files   -> served normally (dotfiles such as .env are blocked)
// - directories      -> their index.php / index.html
// - anything else    -> front controller index.php (Laravel, WordPress, CodeIgniter, custom rewrites)
$__pv_uri = rawurldecode(parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) ?? '/');

if (strpos($__pv_uri, '/__adminer') === 0) {
    $__pv_adminer = getenv('ADMINER_FILE');
    if ($__pv_adminer && is_file($__pv_adminer)) {
        error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING);
        ini_set('display_errors', '0');
        unset($__pv_uri);
        require $__pv_adminer;
        return true;
    }
    http_response_code(404);
    echo 'Adminer is not available';
    return true;
}

$__pv_root = rtrim($_SERVER['DOCUMENT_ROOT'], '/');
$__pv_file = $__pv_root . $__pv_uri;

if (is_file($__pv_file)) {
    if (preg_match('~/\.(?!well-known/)~', $__pv_uri)) {
        http_response_code(403);
        return true;
    }
    return false;
}

if (is_dir($__pv_file)) {
    foreach (['index.php', 'index.html', 'index.htm'] as $__pv_idx) {
        if (is_file(rtrim($__pv_file, '/') . '/' . $__pv_idx)) {
            return false;
        }
    }
}

$__pv_front = $__pv_root . '/index.php';
if (is_file($__pv_front)) {
    $_SERVER['SCRIPT_FILENAME'] = $__pv_front;
    $_SERVER['SCRIPT_NAME'] = '/index.php';
    $_SERVER['PHP_SELF'] = '/index.php';
    chdir($__pv_root);
    unset($__pv_uri, $__pv_root, $__pv_file, $__pv_idx);
    require $__pv_front;
    return true;
}
return false;
