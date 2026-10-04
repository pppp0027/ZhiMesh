package com.pppp.zhimesh.common.config.embeddingstore;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.util.ZhiMeshPropertiesUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pgvector的相关配置
 */
@Slf4j
@Configuration
@ConditionalOnProperty(value = "zhimesh.vector-database", havingValue = "pgvector")
public class PgVectorEmbeddingStoreConfig {

    @Value("${spring.datasource.url}")
    private String dataBaseUrl;

    @Value("${spring.datasource.username}")
    private String dataBaseUserName;

    @Value("${spring.datasource.password}")
    private String dataBasePassword;

    @Resource
    private ZhiMeshProperties adiProperties;

    /**
     * 根据选定的嵌入模型及其生成向量的维度不同来定义不同的表名，项目启动时只使用其中一种<br/>
     * 例如：<br/>
     * adi_knowledge_base_embedding_bge_512（默认）: 本地嵌入模型bge-small-zh-v1.5，维度512<br/>
     * adi_knowledge_base_embedding: 本地嵌入模型all-minilm-l6-v2，维度384<br/>
     * adi_knowledge_base_embedding_qwen_1024: 通义千问嵌入模型，维度1024<br/>
     * adi_knowledge_base_embedding_openai_1536: OpenAI嵌入模型，维度1536<br/>
     * <p>
     * Define different table names based on the selected embedding model and its vector dimension.
     * Only one is used at runtime.<br/>
     * Examples:<br/>
     * adi_knowledge_base_embedding_bge_512: bge-small-zh-v1.5, dimension 512 (default)<br/>
     * adi_knowledge_base_embedding: all-minilm-l6-v2, dimension 384<br/>
     * adi_knowledge_base_embedding_qwen_1024: Qwen embedding model, dimension 1024<br/>
     * adi_knowledge_base_embedding_openai_1536: OpenAI embedding model, dimension 1536<br/>
     *
     * @return embeddingStore
     */
    @Bean(name = "kbEmbeddingStore")
    @Primary
    public EmbeddingStore<TextSegment> initKbEmbeddingStore() {
        log.info("Initializing kbEmbeddingStore...");
        String tableName = "adi_knowledge_base_embedding";
        Pair<String, Integer> pair = ZhiMeshPropertiesUtil.getSuffixAndDimension(adiProperties);
        if (StringUtils.isNotBlank(pair.getLeft())) {
            tableName = tableName + "_" + pair.getLeft();
        }
        return createEmbeddingStore(tableName, pair.getRight());
    }

    /**
     * 角色语义记忆使用的向量库
     *
     * @return EmbeddingStore实例
     */
    @Bean(name = "semanticEmbeddingStore")
    public EmbeddingStore<TextSegment> initSemanticEmbeddingStore() {
        log.info("Initializing semanticEmbeddingStore...");
        String tableName = "adi_character_memory_embedding";
        Pair<String, Integer> pair = ZhiMeshPropertiesUtil.getSuffixAndDimension(adiProperties);
        if (StringUtils.isNotBlank(pair.getLeft())) {
            tableName = tableName + "_" + pair.getLeft();
        }
        return createEmbeddingStore(tableName, pair.getRight());
    }

    /**
     * 角色情景记忆使用的独立向量库。
     * <p>
     * Dedicated vector store for episodic memory, physically isolated from semantic
     * memory so retrieval on either store never competes for top-K with the other.
     *
     * @return EmbeddingStore实例
     */
    @Bean(name = "episodicEmbeddingStore")
    public EmbeddingStore<TextSegment> initEpisodicEmbeddingStore() {
        log.info("Initializing episodicEmbeddingStore...");
        String tableName = "adi_character_episodic_memory_embedding";
        Pair<String, Integer> pair = ZhiMeshPropertiesUtil.getSuffixAndDimension(adiProperties);
        if (StringUtils.isNotBlank(pair.getLeft())) {
            tableName = tableName + "_" + pair.getLeft();
        }
        return createEmbeddingStore(tableName, pair.getRight());
    }

    /**
     * pgvector 专用共享连接池。此前 Builder 的 host/port 形式内部走
     * PGSimpleDataSource——每次查询新建一条 TCP 连接，dev 库在 Tailscale
     * WAN（单 RTT ~60ms）上每次向量查询都多付一整轮握手；三个向量库
     * （KB/语义/情景）共享一个 Hikari 池。
     * 注意：故意不注册为 Spring Bean——容器里出现任何 DataSource Bean 都
     * 会让 Boot 的主数据源自动配置退避，顶掉 MyBatis 的主库；改由本配置
     * 类持有并在 @PreDestroy 关闭。
     * Shared Hikari pool for the three vector stores. The host/port builder
     * path uses PGSimpleDataSource internally, which opens a fresh TCP
     * connection per query (a full WAN handshake each time on Tailscale).
     * Deliberately NOT a Spring bean: any DataSource bean would back off the
     * Boot auto-configured primary datasource.
     */
    private volatile HikariDataSource vectorDataSource;

    @PreDestroy
    void closeVectorDataSource() {
        if (null != vectorDataSource) {
            vectorDataSource.close();
        }
    }

    private DataSource vectorDataSource() {
        HikariDataSource existing = vectorDataSource;
        if (null != existing) {
            return existing;
        }
        synchronized (this) {
            if (null == vectorDataSource) {
                HikariConfig config = new HikariConfig();
                config.setPoolName("pgvector-store");
                config.setJdbcUrl(dataBaseUrl);
                config.setUsername(dataBaseUserName);
                config.setPassword(dataBasePassword);
                // 检索路由并发（dev 8）以内即可；闲时保持 2 条，长间隔空闲查询不冷启动
                config.setMaximumPoolSize(8);
                config.setMinimumIdle(2);
                // WAN 上空闲连接会被中间设备掐断：30s keepalive 与主池策略一致
                config.setKeepaliveTime(30_000);
                vectorDataSource = new HikariDataSource(config);
            }
            return vectorDataSource;
        }
    }

    private EmbeddingStore<TextSegment> createEmbeddingStore(String tableName, int dimension) {
        // 正则表达式匹配
        String regex = "jdbc:postgresql://([^:/]+):(\\d+)/(\\w+).*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(dataBaseUrl);

        String host = "";
        String port = "";
        String databaseName = "";
        if (matcher.matches()) {
            host = matcher.group(1);
            port = matcher.group(2);
            databaseName = matcher.group(3);

            log.info("Host: " + host);
            log.info("Port: " + port);
            log.info("Database: " + databaseName);
        } else {
            throw new RuntimeException("parse url error");
        }
        log.info("Creating PgVectorEmbeddingStore with table name:{},dimension:{}", tableName, dimension);
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(vectorDataSource())
                .dimension(dimension)
                .createTable(true)
                .dropTableFirst(false)
                .table(tableName)
                .build();
    }

}
