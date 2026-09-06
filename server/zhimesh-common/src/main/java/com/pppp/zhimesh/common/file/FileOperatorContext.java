package com.pppp.zhimesh.common.file;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.service.SysConfigService;
import com.pppp.zhimesh.common.vo.SaveRemoteImageResult;
import dev.langchain4j.data.document.Document;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.STORAGE_LOCATION_VALUE_ALI_OSS;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.STORAGE_LOCATION_VALUE_LOCAL;

public class FileOperatorContext {

    private static final Map<Integer, IFileOperator> CONCRETE_OPT = new HashMap<>();

    static {
        CONCRETE_OPT.put(STORAGE_LOCATION_VALUE_LOCAL, new LocalFileOperator());
        CONCRETE_OPT.put(STORAGE_LOCATION_VALUE_ALI_OSS, new AliyunOssFileOperator());
    }

    private final IFileOperator currentOpt;

    public FileOperatorContext(Integer storageLocation) {
        this.currentOpt = CONCRETE_OPT.get(storageLocation);
    }

    public FileOperatorContext() {
        Integer storageLocation = SysConfigService.getIntByKey(ZhiMeshConstant.SysConfigKey.STORAGE_LOCATION, -1);
        this.currentOpt = CONCRETE_OPT.get(storageLocation);
    }

    public static boolean checkIfExist(ZhiMeshFile adiFile) {
        return CONCRETE_OPT.get(adiFile.getStorageLocation()).checkIfExist(adiFile);
    }

    public Pair<String, String> save(MultipartFile file, boolean image, String fileName) {
        return currentOpt.save(file, image, fileName);
    }

    public Pair<String, String> save(byte[] file, boolean image, String fileName) {
        return currentOpt.save(file, image, fileName);
    }

    public SaveRemoteImageResult saveImageFromUrl(String imageUrl, String uuid) {
        return currentOpt.saveImageFromUrl(imageUrl, uuid);
    }

    public static void delete(ZhiMeshFile adiFile) {
        CONCRETE_OPT.get(adiFile.getStorageLocation()).delete(adiFile);
    }

    public static String getFileUrl(ZhiMeshFile adiFile) {
        return CONCRETE_OPT.get(adiFile.getStorageLocation()).getFileUrl(adiFile);
    }

    public static Document loadDocument(ZhiMeshFile adiFile) {
        return CONCRETE_OPT.get(adiFile.getStorageLocation()).loadDocument(adiFile);
    }

    public static int getStorageLocation() {
        return SysConfigService.getIntByKey(ZhiMeshConstant.SysConfigKey.STORAGE_LOCATION, -1);
    }
}
