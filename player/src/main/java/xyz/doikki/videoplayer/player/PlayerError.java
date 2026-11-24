package xyz.doikki.videoplayer.player;

/**
 * 播放器最后一次错误快照。
 *
 * <p>第三方播放器的回调({@link PlayerEventListener#onError()})不带任何参数，播放器内部的
 * what/extra 或 Exo 的 errorCode 在回调那一刻就被丢掉了，界面上只能显示一句"视频播放出错"，
 * 排查时完全看不出是地址失效、403、还是解码不支持。</p>
 *
 * <p>这里在各类播放器报错的第一现场把错误码和通俗描述存下来，界面提示/弹窗直接读取。</p>
 */
public class PlayerError {

    private static volatile String lastMessage = "";

    public static void set(String message) {
        lastMessage = message == null ? "" : message;
    }

    public static String get() {
        return lastMessage;
    }

    public static void clear() {
        lastMessage = "";
    }

    /**
     * IJK / 系统 MediaPlayer 的 what / extra 转成能看懂的描述。
     */
    public static String describeMediaError(int what, int extra) {
        String type;
        switch (what) {
            case 1:
                type = "未知错误";
                break;
            case 100:
                type = "媒体服务端断开";
                break;
            case 200:
                type = "视频解码失败";
                break;
            default:
                type = "错误类型 " + what;
                break;
        }
        StringBuilder sb = new StringBuilder(type);
        sb.append("（what=").append(what).append(", extra=").append(extra).append("）");
        // extra 常见取值来自 libijkffmpeg / Android MediaPlayer
        switch (extra) {
            case -1004:
                sb.append(" 数据无效：地址失效、需要 Referer/UA，或格式不支持");
                break;
            case -1007:
                sb.append(" 数据格式错误");
                break;
            case -1010:
                sb.append(" 不支持该格式/编码");
                break;
            case -1009:
                sb.append(" 网络不可达或被拒绝");
                break;
            case -110:
                sb.append(" 连接超时");
                break;
            case -2:
                sb.append(" 地址打不开");
                break;
            case -5:
                sb.append(" IO 错误（多为网络中断）");
                break;
            default:
                break;
        }
        return sb.toString();
    }

    /**
     * ExoPlayer(Exo2) 的 PlaybackException.errorCode 转成能看懂的描述。
     */
    public static String describeExoError(int code) {
        String desc;
        switch (code) {
            case 1000:
                desc = "未知错误";
                break;
            case 1001:
                desc = "远端错误";
                break;
            case 1002:
                desc = "落后直播窗口";
                break;
            case 1003:
                desc = "超时";
                break;
            case 2000:
                desc = "IO 未分类错误";
                break;
            case 2001:
                desc = "网络连接失败";
                break;
            case 2002:
                desc = "网络连接超时";
                break;
            case 2003:
                desc = "返回内容不是有效媒体（Content-Type 异常）";
                break;
            case 2004:
                desc = "HTTP 状态码异常（403/404 等）";
                break;
            case 2005:
                desc = "地址不存在（404）";
                break;
            case 2006:
                desc = "无访问权限（403，或需要 Referer/UA）";
                break;
            case 2007:
                desc = "系统禁止明文 HTTP 请求";
                break;
            case 2008:
                desc = "读取位置越界";
                break;
            case 3000:
                desc = "容器已损坏";
                break;
            case 3001:
                desc = "播放列表清单损坏";
                break;
            case 3002:
                desc = "容器格式不支持";
                break;
            case 3003:
                desc = "播放列表清单格式不支持";
                break;
            case 4000:
                desc = "解码器初始化失败";
                break;
            case 4001:
                desc = "解码器查询失败";
                break;
            case 4002:
                desc = "解码失败";
                break;
            case 4003:
                desc = "编码超出设备解码能力";
                break;
            case 4004:
                desc = "编码格式不支持（常见于 HEVC 10bit / AV1）";
                break;
            case 5000:
                desc = "音轨初始化失败";
                break;
            case 5001:
                desc = "音轨写入失败";
                break;
            case 6000:
                desc = "DRM 授权错误";
                break;
            default:
                desc = "错误";
                break;
        }
        return desc + "（" + code + "）";
    }
}
