import os
import requests
from importlib.machinery import SourceFileLoader
import json

# ==== DNS 补丁防自噬：app 被导入时（解释器刚启动、任何蜘蛛还没加载）先存下 ====
# ==== 原始的 socket.getaddrinfo。个别蜘蛛会在模块里接管 getaddrinfo 并把「当 ====
# ==== 前的」getaddrinfo 存为原函数；load_module 每次创建都会重执行模块，第二 ====
# ==== 次执行存到的就是它自己的补丁，补丁调原函数即调自己，无限递归拖死整个 ====
# ==== 解释器的 DNS。故每次加载蜘蛛模块前先把 getaddrinfo 还原为原始版，任何 ====
# ==== 补丁只能套在真原函数上，重复加载/多蜘蛛叠套都成不了环。 ====
import socket as _socket

_PRISTINE_GETADDRINFO = _socket.getaddrinfo

# ==== 请求日志（源调试）：给 requests 装全局钩子，日志调试开启时把每条请求 ====
# ==== （[站点 方法] 方法 完整网址 -> 状态（耗时））以明文条目记入壳日志仓蜘蛛类 ====
import time as _time
from urllib.parse import urlparse as _urlparse

_REQLOG = {'installed': False, 'store': None, 'category': None}
_REQCTX = {'label': ''}


def _reqlog_write(method, url, tail):
    try:
        label = _REQCTX.get('label') or ''
        # 内容请求的真实域名（py 源运行时用的站，含竞速后的域名），供主页/网页播放用；与日志开关无关。
        # 只记内容方法（首页/分类/详情/搜索），不记播放（播放常走解析/CDN，不是站域名）。
        for _suffix in (' homeContent', ' homeVideoContent', ' categoryContent', ' searchContent', ' detailContent'):
            if label.endswith(_suffix):
                try:
                    from com.github.catvod.crawler import SpiderDebug as _SD
                    _parts = _urlparse(str(url))
                    if _parts.hostname:
                        _SD.recordDetailOrigin(label[: -len(_suffix)], _parts.scheme + '://' + _parts.netloc)
                except Exception:
                    pass
                break
        if _REQLOG['store'] is None:
            from com.github.catvod.crawler import DebugLogStore as _Store
            from com.github.catvod.crawler.diagnostics import LogCategory as _Category
            _REQLOG['store'] = _Store
            _REQLOG['category'] = _Category
        if not _REQLOG['store'].isEnabled():
            return
        head = '[' + label + '] ' if label else ''
        _REQLOG['store'].addPlain(_REQLOG['category'].SPIDER, 'python-spider',
                                  head + str(method).upper() + ' ' + str(url) + tail)
    except Exception:
        pass


def _reqlog_skip(url):
    try:
        parts = _urlparse(str(url))
        if (parts.hostname or '') in ('127.0.0.1', 'localhost', '::1'):
            return True
        path = (parts.path or '').lower()
        return path.endswith('.ts') or path.endswith('.m4s') or path.endswith('.key')
    except Exception:
        return False


def _reqlog_install():
    if _REQLOG['installed']:
        return
    _REQLOG['installed'] = True
    try:
        _orig = requests.sessions.Session.request

        def _request(self, method, url, **kwargs):
            if _reqlog_skip(url):
                return _orig(self, method, url, **kwargs)
            _t0 = _time.time()
            try:
                _resp = _orig(self, method, url, **kwargs)
            except Exception as _e:
                _reqlog_write(method, url, ' -> 失败 %s (%dms)' % (_e.__class__.__name__, int((_time.time() - _t0) * 1000)))
                raise
            _reqlog_write(method, url, ' -> %s (%dms)' % (getattr(_resp, 'status_code', 0), int((_time.time() - _t0) * 1000)))
            return _resp

        requests.sessions.Session.request = _request
    except Exception:
        pass


_reqlog_install()


def _tag(ru, method):
    try:
        key = getattr(ru, 'siteKey', '') or type(ru).__module__
    except Exception:
        key = ''
    _REQCTX['label'] = ('%s %s' % (key, method)).strip()


def spider(cache, api):
    try:
        _socket.getaddrinfo = _PRISTINE_GETADDRINFO
    except Exception:
        pass
    name = os.path.basename(api)
    path = cache + '/' + name
    download(path, api)
    name = name.split('.')[0]
    return SourceFileLoader(name, path).load_module().Spider()


def download(path, api):
    if api.startswith('http'):
        writeFile(path, redirect(api).content)
    else:
        writeFile(path, str.encode(api))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    rsp = requests.get(url, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'])
    else:
        return rsp


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    _tag(ru, 'getDependence')
    result = ru.getDependence()
    return result


def getName(ru):
    _tag(ru, 'getName')
    result = ru.getName()
    return result


def init(ru, extend):
    _tag(ru, 'init')
    ru.init(extend)


def homeContent(ru, filter):
    _tag(ru, 'homeContent')
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    _tag(ru, 'homeVideoContent')
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    _tag(ru, 'categoryContent')
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    _tag(ru, 'detailContent')
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    _tag(ru, 'searchContent')
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    _tag(ru, 'playerContent')
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    _tag(ru, 'liveContent')
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    _tag(ru, 'localProxy')
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    _tag(ru, 'action')
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    _tag(ru, 'destroy')
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()
