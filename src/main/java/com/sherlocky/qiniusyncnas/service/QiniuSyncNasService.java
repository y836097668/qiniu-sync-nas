package com.sherlocky.qiniusyncnas.service;

import com.alibaba.fastjson.JSON;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.sherlocky.common.util.MessagePushUtils;
import com.sherlocky.qiniusyncnas.entity.SyncResult;
import com.sherlocky.qiniusyncnas.qiniu.config.QiNiuProperties;
import com.sherlocky.qiniusyncnas.qiniu.service.IQiniuService;
import com.sherlocky.qiniusyncnas.util.QiniuFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步七牛文件到NAS本地
 * @author: zhangcx
 * @date: 2019/6/13 20:05
 */
@Slf4j
@Service
public class QiniuSyncNasService {
    @Autowired
    private IQiniuService qiniuService;
    @Autowired
    private QiNiuProperties qiNiuProperties;
    /** 每次迭代的长度限制，推荐值 1000 */
    @Value("${sync.qiniu.prefix:}")
    private String prefix;
    @Value("${sync.qiniu.limit:1000}")
    private Integer limit;
    @Value("${sync.qiniu.delimiter:}")
    private String delimiter;
    @Value("${serverchan.sckey}")
    private String serverchanSckey;

    /** 同步操作计数 */
    private static AtomicInteger syncCount = new AtomicInteger(0);

    /**
     * 同步七牛OSS文件
     * @return count 同步文件的总个数
     */
    public SyncResult sync() {
        // 计数器自增后，如果大于1，则表示有同步操作在进行中
        if (syncCount.incrementAndGet() > 1) {
            log.error("$$$$$$ 已有同步操作在进行中。");
            return new SyncResult("同步进行中。");
        }
        FileListing fl = null;
        String marker = null;
        boolean isEOF = false;
        long successCount = 0, totalCount = 0;
        try {
            while (!isEOF) {
                fl = qiniuService.listFile(prefix, marker, limit, delimiter);
                // 列举操作是否已到所有文件列表结尾，如果为true表示无需再发送列举请求
                isEOF = fl.isEOF();
                marker = fl.marker;
                totalCount += fl.items.length;
                successCount += handleSync(fl);
            }
        } catch (QiniuException e) {
            log.error("$$$$$$ 从七牛获取空间文件列表失败", e);
        }
        if (log.isDebugEnabled()) {
            log.debug(JSON.toJSONString(qiNiuProperties));
        }
        if (log.isInfoEnabled()) {
            log.info("### 当前存储空间共有 " + totalCount + " 个文件，本次成功同步了 " + successCount + " 个~");
        }
        sendSyncMessage(totalCount, successCount);
        // 同步结束：归0
        syncCount.set(0);
        return new SyncResult(totalCount, successCount);
    }

    /**
     * 推送同步结果消息（有失败的才推送）
     * @param totalCount
     * @param successCount
     */
    private void sendSyncMessage(long totalCount, long successCount) {
        if (StringUtils.isNotBlank(serverchanSckey) && totalCount > successCount) {
            MessagePushUtils.push(serverchanSckey, "七牛文件同步到NAS异常",
                    String.format("当前存储空间共有 %d 个文件，本次成功同步了 %d 个", totalCount, successCount));
        }
    }

    private long handleSync(FileListing fileListing) {
        if (fileListing == null) {
            return 0;
        }
        FileInfo[] fis = fileListing.items;
        if (ArrayUtils.isEmpty(fis)) {
            return 0;
        }
        // 返回成功下载的个数
        return Arrays.stream(fis).filter((fileInfo) -> {
            return downloadFile(fileInfo);
        }).count();
    }

    private boolean downloadFile(FileInfo fileInfo) {
        if (log.isDebugEnabled()) {
            log.debug(JSON.toJSONString(fileInfo));
        }
        String fileDownloadUrl = qiniuService.getDownloadUrl(fileInfo.key);
        if (StringUtils.isBlank(fileDownloadUrl)) {
            log.error("$$$ 文件下载地址为空，下载失败！");
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("### 文件： " + fileInfo.key + " -> " + fileDownloadUrl);
        }
        // 将文件下载写入到磁盘（按照七牛路径格式，以 / 分隔目录层级）
        return QiniuFileUtils.downloadFile(fileDownloadUrl, fileInfo.key, fileInfo.fsize, fileInfo.putTime);
    }
}
