package com.github.tvbox.osc.bean;

/**
 * DLNA / UPnP 投屏设备模型
 * 保存从设备描述文档中解析出的、用于投屏控制(AVTransport)的必要信息
 */
public class CastDevice {
    public String uuid;           // 设备唯一标识(UDN)
    public String name;           // 友好名称, 例如 "客厅的小米电视"
    public String modelName;      // 型号
    public String descriptionUrl; // LOCATION, 设备描述文档地址
    public String baseUrl;        // 设备服务根地址, 例如 http://192.168.1.10:8200
    public String controlUrl;     // AVTransport 控制地址(已补全为绝对地址)
    public String eventUrl;       // AVTransport 事件地址

    public CastDevice() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CastDevice that = (CastDevice) o;
        if (uuid != null) return uuid.equals(that.uuid);
        return descriptionUrl != null && descriptionUrl.equals(that.descriptionUrl);
    }

    @Override
    public int hashCode() {
        return uuid != null ? uuid.hashCode() : (descriptionUrl != null ? descriptionUrl.hashCode() : 0);
    }
}
