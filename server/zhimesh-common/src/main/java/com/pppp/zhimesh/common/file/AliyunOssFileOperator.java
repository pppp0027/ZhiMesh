package com.pppp.zhimesh.common.file;

import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.vo.SaveRemoteImageResult;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.UrlDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.POI_DOC_TYPES;
import static com.pppp.zhimesh.common.enums.ErrorEnum.B_DELETE_FILE_ERROR;

@Slf4j
public class AliyunOssFileOperator implements IFileOperator {

    private static AliyunOssFileHelper aliyunOssFileHelper;

    @Override
    public boolean checkIfExist(ZhiMeshFile adiFile) {
        return aliyunOssFileHelper.doesObjectExist(getObjectName(adiFile));
    }

    @Override
    public Pair<String, String> save(MultipartFile file, boolean image, String fileName) {
        String objectName;
        String ext;
        if (fileName.contains(".")) {
            ext = LocalFileUtil.getFileExtension(fileName);
        } else {
            ext = LocalFileUtil.getFileExtension(file.getOriginalFilename());
        }
        objectName = fileName + "." + ext;
        try {
            aliyunOssFileHelper.saveObj(file.getBytes(), objectName);
        } catch (IOException e) {
            throw new BaseException(B_DELETE_FILE_ERROR);
        }
        return new ImmutablePair<>(aliyunOssFileHelper.getUrl(objectName), ext);
    }

    @Override
    public Pair<String, String> save(byte[] file, boolean image, String name) {
        String ext = LocalFileUtil.getFileExtension(name);
        aliyunOssFileHelper.saveObj(file, name);
        return new ImmutablePair<>(aliyunOssFileHelper.getUrl(name), ext);
    }

    @Override
    public SaveRemoteImageResult saveImageFromUrl(String imageUrl, String fileName) {
        String filePath = LocalFileUtil.saveFromUrl(imageUrl, fileName, "png");
        byte[] bytes = LocalFileUtil.readBytes(filePath);
        String ext = LocalFileUtil.getFileExtension(filePath);
        String objName = fileName + "." + ext;
        aliyunOssFileHelper.saveObj(bytes, objName);
        try {
            //传到oss后把本地临时文件删除
            // Delete local temp file after uploading to OSS
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            throw new BaseException(B_DELETE_FILE_ERROR);
        }
        //对于OSS，存储的是对象名称，而不是完整URL
        // For OSS, store the object name instead of the full URL
        filePath = objName;
        return SaveRemoteImageResult.builder().ext(ext).originalName(fileName).pathOrUrl(filePath).build();
    }

    @Override
    public void delete(ZhiMeshFile adiFile) {
        if (StringUtils.isBlank(adiFile.getPath())) {
            return;
        }
        aliyunOssFileHelper.deleteObjs(List.of(getObjectName(adiFile)));
    }

    @Override
    public String getFileUrl(ZhiMeshFile adiFile) {
        return aliyunOssFileHelper.getUrl(getObjectName(adiFile));
    }

    @Override
    public Document loadDocument(ZhiMeshFile adiFile) {
        Document result = null;
        String path = adiFile.getPath();
        String ext = adiFile.getExt();
        if (ext.equalsIgnoreCase("txt") || ext.equalsIgnoreCase("md") || ext.equalsIgnoreCase("markdown")) {
            result = UrlDocumentLoader.load(path, new TextDocumentParser());
        } else if (ext.equalsIgnoreCase("pdf")) {
            result = UrlDocumentLoader.load(path, new ApachePdfBoxDocumentParser());
        } else if (ArrayUtils.contains(POI_DOC_TYPES, adiFile.getExt())) {
            result = UrlDocumentLoader.load(path, new ApachePoiDocumentParser());
        }
        return result;
    }

    public static String getObjectName(ZhiMeshFile adiFile) {
        return adiFile.getUuid() + "." + adiFile.getExt();
    }

    public static void init(AliyunOssFileHelper aliyunOssFileHelper) {
        AliyunOssFileOperator.aliyunOssFileHelper = aliyunOssFileHelper;
    }
}
