import yt_dlp
import json

_ydl = None
_quickjs_bin = None

def _get_ydl(quickjs_bin: str):
    global _ydl, _quickjs_bin
    if _ydl is None or _quickjs_bin != quickjs_bin:
        _quickjs_bin = quickjs_bin
        _ydl = yt_dlp.YoutubeDL({
            'format': 'bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio',
            'js_runtimes': {'quickjs': {'path': quickjs_bin}},
            'quiet': True,
            'no_warnings': True,
            'skip_download': True,
            'noplaylist': True,
            'no_color': True,
            'ignoreerrors': False,
            'noprogress': True,
            'geo_bypass': True,
            'no_check_certificate': True,
            'youtube_include_dash_manifest': False,
            'youtube_include_hls_manifest': False,
            'extractor_args': {
                'youtube': {
                    'skip': ['hls', 'dash', 'translated_subs'],
                }
            },
        })
    return _ydl

def prewarm(quickjs_bin: str):
    _get_ydl(quickjs_bin)

def download(quickjs_bin: str, video_id: str) -> str:
    ydl = _get_ydl(quickjs_bin)
    info = ydl.extract_info(video_id, download=False)
    best = None
    best_abr = -1
    for f in (info.get('formats') or []):
        if f.get('vcodec', 'none') != 'none':
            continue
        abr = f.get('abr') or 0
        if abr > best_abr:
            best_abr = abr
            best = f
    if best is None:
        best = info
    result = {
        'id': info.get('id'),
        'format_id': best.get('format_id'),
        'url': best.get('url'),
        'filesize': best.get('filesize') or best.get('filesize_approx') or 0,
        'formats': [
            {
                'format_id': f.get('format_id'),
                'url': f.get('url'),
                'abr': f.get('abr'),
            }
            for f in (info.get('formats') or [])
            if f.get('vcodec', 'none') == 'none' and f.get('url')
        ]
    }
    return json.dumps(result)

def upgrade(package_name):
    global _ydl
    try:
        import ensurepip
        ensurepip.bootstrap()
    except Exception as e:
        print(f"Error running ensurepip: {e}")
    try:
        from pip._internal import main as pip_main
        pip_main(['install', '--upgrade', package_name])
        print(f"Successfully upgraded {package_name}")
        _ydl = None
    except Exception as e:
        print(f"Error upgrading package {package_name}: {e}")
