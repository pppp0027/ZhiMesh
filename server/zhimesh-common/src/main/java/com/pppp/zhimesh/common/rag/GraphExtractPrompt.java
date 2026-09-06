package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Copy https://github.com/microsoft/graphrag/blob/main/graphrag/prompts/index/extract_graph.py
 */
public class GraphExtractPrompt {
    /**
     * Query-time prompt. This is intentionally smaller than the document graph
     * extraction prompt: retrieval needs only entity names, not a second graph.
     */
    public static final String GRAPH_QUERY_ENTITY_PROMPT = """
            Identify the named entities in the user question that are useful for searching an existing knowledge graph.
            Return exactly one valid JSON object in this shape:
            {"entities":[{"name":"..."}]}
            Do not answer the question. Do not use Markdown, code fences, explanations, or text outside the JSON object.
            Preserve entity names from the question. Do not include generic question words, dates, or unsupported concepts.
            If no named entity can be identified, return {"entities":[]}.
            User question:
            {input_text}
            """;

    public static String buildQueryEntityPrompt(String inputText) {
        return GRAPH_QUERY_ENTITY_PROMPT.replace("{input_text}", inputText);
    }

    public static final String GRAPH_EXTRACTION_JSON_PROMPT = """
            Extract entities and relationships from the input text.
            Return exactly one valid JSON object. Do not use Markdown, code fences, explanations, or text outside the JSON object.
            Use only these core entity types: [{entity_types}]. If none fits safely, use UNKNOWN as an explicit fallback.
            Type guidance:
            PERSON=individual person; ORGANIZATION=company, institution, department or team;
            LOCATION=geographic or physical place; PRODUCT=named product or solution;
            SYSTEM=software or business system; SERVICE=technical or business-facing service;
            TECHNOLOGY=technology, framework, database, middleware, protocol or platform;
            DOCUMENT=manual, specification, report, policy or standard;
            CONCEPT=abstract term, principle or theory; PROCESS=workflow, procedure or operation;
            EVENT=meeting, incident, release or activity; ROLE=job role, responsibility or position.
            Do not create a new type for versions, numbers, statuses or configuration values;
            keep those details in the description. UNKNOWN is only a fallback and must not be used when a core type fits.
            Disambiguate the entity itself, not words appearing in its name. A company, consortium, institution or team is ORGANIZATION even when its name contains a product word such as "robot". A named model, device or material sold or built by an organization is PRODUCT.
            For example, in Chinese company archives, "曜穹机器人" is ORGANIZATION when the text says it is a company, while "赤脊七型" is PRODUCT when the text says it is a product/model. Never emit the same real-world entity under multiple types in one response.
            Create a vertex only when the item has a stable identity and independent retrieval value. Named people, organizations, places, products, systems, documents, plans, events and domain concepts can be vertices. Generic nouns and scalar attributes such as cash, brand, percentage, amount, duration, status, slogan, threshold and statistical measure must normally be placed in the nearest entity or relationship properties object instead of becoming standalone entities.
            Give every entity a salience score from 1 to 10. Use 5 or above only when the entity is useful as an independent query anchor. Avoid extracting every noun phrase or every possible entity pair.
            Use entity names consistently in entities and relationships.
            Preserve every entity name exactly as it appears in the input (apart from surrounding whitespace). canonical_name is the most complete source-supported name for that real-world entity; never invent a full name absent from the input. aliases contains only other names or abbreviations explicitly present in the input. Entity type values must remain the English uppercase enum values above.
            Write every entity description and relationship description in the input text's primary language. For Chinese input, descriptions must be natural Chinese. Do not add an English translation, English explanatory sentence, or facts unsupported by the input. English product names and abbreviations may appear only when they already occur in the input.
            Relationship type is an uppercase SNAKE_CASE semantic predicate. Prefer this vocabulary when it fits: PART_OF, SUBSIDIARY_OF, OWNS, OWNED_BY, LOCATED_IN, FOUNDED_BY, WORKS_FOR, CREATED_BY, PRODUCES, PROVIDES_TO, SUPPLIES_TO, USES, DEPENDS_ON, COOPERATES_WITH, ACQUIRES, INVESTS_IN, PARTICIPATES_IN, HAS_ROLE, IMPLEMENTS, CAUSES, AFFECTS, PRECEDES, FOLLOWS, CONTRADICTS, HAS_METRIC, RELATED_TO. Use one concise new uppercase SNAKE_CASE predicate only when none fits.
            polarity is true for an asserted positive relationship and false when the source explicitly denies the relationship. Do not turn “A is not a subsidiary of B” into a positive SUBSIDIARY_OF edge. status must be one of ASSERTED, PLANNED, PROPOSED, HISTORICAL, DISPUTED, CONDITIONAL.
            {
              "entities": [{"name": "...", "canonical_name": "...", "aliases": [], "type": "...", "description": "...", "properties": {}, "salience": 8}],
              "relationships": [{"source": "...", "target": "...", "type": "...", "polarity": true, "status": "ASSERTED", "description": "...", "properties": {}, "weight": 1}]
            }
            Entity names, canonical names, types and descriptions, and relationship source, target, type and descriptions, must be non-empty. Every relationship endpoint must exactly match an entity name in the entities array. Weight must be between 0 and 10.
            Prefer a connected, useful graph, but keep only explicit relationships with retrieval value. Do not return an empty result merely because the text has no person name. Do not extract generic filler words, dates, attributes or incidental concepts as standalone entities. A single central entity may be returned only when the text truly contains no supported relationship.
            If none are present, return {"entities":[],"relationships":[]}.
            Chinese example:
            Input: 曜穹机器人是一家工业机器人公司，发布了赤脊七型巡检机器人。
            Output: {"entities":[{"name":"曜穹机器人","canonical_name":"曜穹机器人","aliases":[],"type":"ORGANIZATION","description":"一家发布工业巡检机器人的公司","properties":{},"salience":9},{"name":"赤脊七型","canonical_name":"赤脊七型","aliases":[],"type":"PRODUCT","description":"曜穹机器人发布的工业巡检机器人产品","properties":{},"salience":8}],"relationships":[{"source":"曜穹机器人","target":"赤脊七型","type":"PRODUCES","polarity":true,"status":"ASSERTED","description":"曜穹机器人发布了赤脊七型","properties":{},"weight":8}]}
            Input text:
            {input_text}
            """.replace("{entity_types}", String.join(",", ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES));

    public static String buildJsonExtractionPrompt(String inputText) {
        return GRAPH_EXTRACTION_JSON_PROMPT.replace("{input_text}", inputText);
    }

    public static String buildJsonRepairPrompt(String inputText, String invalidJson, String issues) {
        return """
                Repair the graph extraction JSON using the source text as the only factual authority.
                Return exactly one valid JSON object with the same schema, including canonical_name, aliases, properties, salience, and relationship type, polarity and status. Do not use Markdown or explanations.
                Keep only entities and relationships supported by the source. Preserve entity names from the source and use only these types: [{entity_types}].
                Every entity and relationship description must be non-empty and written in the source text's primary language.
                For Chinese source text, use natural Chinese descriptions and remove English translations or explanatory prose that is absent from the source. English names or abbreviations are allowed only when present in the source.
                Resolve duplicate names to one correct type. A company/institution/team is ORGANIZATION; a named model/device/material is PRODUCT; an action phrase such as “收购某部门” is EVENT rather than ORGANIZATION. Move generic metrics, amounts, durations and statuses into properties. Every relationship endpoint must exactly match one entity name. Relationship type must be uppercase SNAKE_CASE, polarity must be boolean, status must be ASSERTED, PLANNED, PROPOSED, HISTORICAL, DISPUTED or CONDITIONAL, and weight must be between 0 and 10.
                Detected issues: {issues}
                Source text:
                {input_text}
                JSON to repair:
                {invalid_json}
                """
                .replace("{entity_types}", String.join(",", ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES))
                .replace("{issues}", issues)
                .replace("{input_text}", inputText)
                .replace("{invalid_json}", invalidJson);
    }
    public static final String GRAPH_EXTRACTION_PROMPT = """
            -Goal-
            Given a text document that is potentially relevant to this activity and a list of entity types, identify all entities of those types from the text and all relationships among the identified entities.
            
            -Steps-
            1. Identify all entities. For each identified entity, extract the following information:
            - entity_name: Name of the entity, capitalized
            - entity_type: One of the following types: [{entity_types}]
            - entity_description: Comprehensive description of the entity's attributes and activities
            Format each entity as ("entity"{tuple_delimiter}<entity_name>{tuple_delimiter}<entity_type>{tuple_delimiter}<entity_description>)
            
            2. From the entities identified in step 1, identify all pairs of (source_entity, target_entity) that are *clearly related* to each other.
            For each pair of related entities, extract the following information:
            - source_entity: name of the source entity, as identified in step 1
            - target_entity: name of the target entity, as identified in step 1
            - relationship_description: explanation as to why you think the source entity and the target entity are related to each other
            - relationship_strength: a numeric score indicating strength of the relationship between the source entity and target entity
            Format each relationship as ("relationship"{tuple_delimiter}<source_entity>{tuple_delimiter}<target_entity>{tuple_delimiter}<relationship_description>{tuple_delimiter}<relationship_strength>)
            
            3. Return output in the same language as the input text as a single list of all the entities and relationships identified in steps 1 and 2. Use **{record_delimiter}** as the list delimiter.
            
            4. When finished, output {completion_delimiter}
            
            ######################
            -Examples-
            ######################
            Example 1:
            Entity_types: ORGANIZATION,PERSON
            Text:
            The Verdantis's Central Institution is scheduled to meet on Monday and Thursday, with the institution planning to release its latest policy decision on Thursday at 1:30 p.m. PDT, followed by a press conference where Central Institution Chair Martin Smith will take questions. Investors expect the Market Strategy Committee to hold its benchmark interest rate steady in a range of 3.5%-3.75%.
            ######################
            Output:
            ("entity"{tuple_delimiter}CENTRAL INSTITUTION{tuple_delimiter}ORGANIZATION{tuple_delimiter}The Central Institution is the Federal Reserve of Verdantis, which is setting interest rates on Monday and Thursday)
            {record_delimiter}
            ("entity"{tuple_delimiter}MARTIN SMITH{tuple_delimiter}PERSON{tuple_delimiter}Martin Smith is the chair of the Central Institution)
            {record_delimiter}
            ("entity"{tuple_delimiter}MARKET STRATEGY COMMITTEE{tuple_delimiter}ORGANIZATION{tuple_delimiter}The Central Institution committee makes key decisions about interest rates and the growth of Verdantis's money supply)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MARTIN SMITH{tuple_delimiter}CENTRAL INSTITUTION{tuple_delimiter}Martin Smith is the Chair of the Central Institution and will answer questions at a press conference{tuple_delimiter}9)
            {completion_delimiter}
            
            ######################
            Example 2:
            Entity_types: ORGANIZATION
            Text:
            TechGlobal's (TG) stock skyrocketed in its opening day on the Global Exchange Thursday. But IPO experts warn that the semiconductor corporation's debut on the public markets isn't indicative of how other newly listed companies may perform.
            
            TechGlobal, a formerly public company, was taken private by Vision Holdings in 2014. The well-established chip designer says it powers 85% of premium smartphones.
            ######################
            Output:
            ("entity"{tuple_delimiter}TECHGLOBAL{tuple_delimiter}ORGANIZATION{tuple_delimiter}TechGlobal is a stock now listed on the Global Exchange which powers 85% of premium smartphones)
            {record_delimiter}
            ("entity"{tuple_delimiter}VISION HOLDINGS{tuple_delimiter}ORGANIZATION{tuple_delimiter}Vision Holdings is a firm that previously owned TechGlobal)
            {record_delimiter}
            ("relationship"{tuple_delimiter}TECHGLOBAL{tuple_delimiter}VISION HOLDINGS{tuple_delimiter}Vision Holdings formerly owned TechGlobal from 2014 until present{tuple_delimiter}5)
            {completion_delimiter}
            
            ######################
            Example 3:
            Entity_types: ORGANIZATION,GEO,PERSON
            Text:
            Five Aurelians jailed for 8 years in Firuzabad and widely regarded as hostages are on their way home to Aurelia.
            
            The swap orchestrated by Quintara was finalized when $8bn of Firuzi funds were transferred to financial institutions in Krohaara, the capital of Quintara.
            
            The exchange initiated in Firuzabad's capital, Tiruzia, led to the four men and one woman, who are also Firuzi nationals, boarding a chartered flight to Krohaara.
            
            They were welcomed by senior Aurelian officials and are now on their way to Aurelia's capital, Cashion.
            
            The Aurelians include 39-year-old businessman Samuel Namara, who has been held in Tiruzia's Alhamia Prison, as well as journalist Durke Bataglani, 59, and environmentalist Meggie Tazbah, 53, who also holds Bratinas nationality.
            ######################
            Output:
            ("entity"{tuple_delimiter}FIRUZABAD{tuple_delimiter}GEO{tuple_delimiter}Firuzabad held Aurelians as hostages)
            {record_delimiter}
            ("entity"{tuple_delimiter}AURELIA{tuple_delimiter}GEO{tuple_delimiter}Country seeking to release hostages)
            {record_delimiter}
            ("entity"{tuple_delimiter}QUINTARA{tuple_delimiter}GEO{tuple_delimiter}Country that negotiated a swap of money in exchange for hostages)
            {record_delimiter}
            {record_delimiter}
            ("entity"{tuple_delimiter}TIRUZIA{tuple_delimiter}GEO{tuple_delimiter}Capital of Firuzabad where the Aurelians were being held)
            {record_delimiter}
            ("entity"{tuple_delimiter}KROHAARA{tuple_delimiter}GEO{tuple_delimiter}Capital city in Quintara)
            {record_delimiter}
            ("entity"{tuple_delimiter}CASHION{tuple_delimiter}GEO{tuple_delimiter}Capital city in Aurelia)
            {record_delimiter}
            ("entity"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}PERSON{tuple_delimiter}Aurelian who spent time in Tiruzia's Alhamia Prison)
            {record_delimiter}
            ("entity"{tuple_delimiter}ALHAMIA PRISON{tuple_delimiter}GEO{tuple_delimiter}Prison in Tiruzia)
            {record_delimiter}
            ("entity"{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}PERSON{tuple_delimiter}Aurelian journalist who was held hostage)
            {record_delimiter}
            ("entity"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}PERSON{tuple_delimiter}Bratinas national and environmentalist who was held hostage)
            {record_delimiter}
            ("relationship"{tuple_delimiter}FIRUZABAD{tuple_delimiter}AURELIA{tuple_delimiter}Firuzabad negotiated a hostage exchange with Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}QUINTARA{tuple_delimiter}AURELIA{tuple_delimiter}Quintara brokered the hostage exchange between Firuzabad and Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}QUINTARA{tuple_delimiter}FIRUZABAD{tuple_delimiter}Quintara brokered the hostage exchange between Firuzabad and Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}ALHAMIA PRISON{tuple_delimiter}Samuel Namara was a prisoner at Alhamia prison{tuple_delimiter}8)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}Samuel Namara and Meggie Tazbah were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}Samuel Namara and Durke Bataglani were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}Meggie Tazbah and Durke Bataglani were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}FIRUZABAD{tuple_delimiter}Samuel Namara was a hostage in Firuzabad{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}FIRUZABAD{tuple_delimiter}Meggie Tazbah was a hostage in Firuzabad{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}FIRUZABAD{tuple_delimiter}Durke Bataglani was a hostage in Firuzabad{tuple_delimiter}2)
            {completion_delimiter}
            
            ######################
            -Real Data-
            ######################
            Entity_types: {entity_types}
            Text: {input_text}
            ######################
            Output:""".replace("{tuple_delimiter}", ZhiMeshConstant.GRAPH_TUPLE_DELIMITER)
            .replace("{entity_types}", String.join(",", ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES))
            .replace("{completion_delimiter}", ZhiMeshConstant.GRAPH_COMPLETION_DELIMITER)
            .replace("{record_delimiter}", ZhiMeshConstant.GRAPH_RECORD_DELIMITER);

    public static final String GRAPH_EXTRACTION_PROMPT_CN = """
            -目标-
            给定一个可能与此活动相关的文本文档以及实体类型列表，从文本中识别出所有这些类型的实体以及识别出的实体之间的所有关系。
                        
            -步骤-
            1. 识别所有实体。对于每个识别出的实体，提取以下信息：
            - entity_name：实体的名称，首字母大写
            - entity_type：以下类型之一：[{entity_types}]
            - entity_description：实体的属性和活动的全面描述
            将每个实体格式化为 ("entity"{tuple_delimiter}<entity_name>{tuple_delimiter}<entity_type>{tuple_delimiter}<entity_description>)
                        
            2. 从步骤1中识别出的实体中，识别出所有明确相关的 (source_entity, target_entity) 对。
            对于每对相关的实体，提取以下信息：
            - source_entity：在步骤1中识别的源实体的名称
            - target_entity：在步骤1中识别的目标实体的名称
            - relationship_description：解释你认为源实体和目标实体之间相关的原因
            - relationship_strength：一个表示源实体和目标实体之间关系强度的数字分数
            将每个关系格式化为 ("relationship"{tuple_delimiter}<source_entity>{tuple_delimiter}<target_entity>{tuple_delimiter}<relationship_description>{tuple_delimiter}<relationship_strength>)
                        
            3. 以与输入文本相同的语言返回输出，作为所有在步骤1和步骤2中识别的实体和关系的列表。使用 **{record_delimiter}** 作为列表分隔符。
                        
            4. 完成时，输出 {completion_delimiter}
                        
            ######################
            -示例-
            ######################
            示例 1:
            Entity_types: ORGANIZATION,PERSON
            文本:
            The Verdantis's Central Institution is scheduled to meet on Monday and Thursday, with the institution planning to release its latest policy decision on Thursday at 1:30 p.m. PDT, followed by a press conference where Central Institution Chair Martin Smith will take questions. Investors expect the Market Strategy Committee to hold its benchmark interest rate steady in a range of 3.5%-3.75%.
            ######################
            输出:
            ("entity"{tuple_delimiter}CENTRAL INSTITUTION{tuple_delimiter}ORGANIZATION{tuple_delimiter}The Central Institution is the Federal Reserve of Verdantis, which is setting interest rates on Monday and Thursday)
            {record_delimiter}
            ("entity"{tuple_delimiter}MARTIN SMITH{tuple_delimiter}PERSON{tuple_delimiter}Martin Smith is the chair of the Central Institution)
            {record_delimiter}
            ("entity"{tuple_delimiter}MARKET STRATEGY COMMITTEE{tuple_delimiter}ORGANIZATION{tuple_delimiter}The Central Institution committee makes key decisions about interest rates and the growth of Verdantis's money supply)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MARTIN SMITH{tuple_delimiter}CENTRAL INSTITUTION{tuple_delimiter}Martin Smith is the Chair of the Central Institution and will answer questions at a press conference{tuple_delimiter}9)
            {completion_delimiter}
                        
            ######################
            示例 2:
            Entity_types: ORGANIZATION
            文本:
            TechGlobal's (TG) stock skyrocketed in its opening day on the Global Exchange Thursday. But IPO experts warn that the semiconductor corporation's debut on the public markets isn't indicative of how other newly listed companies may perform.
                        
            TechGlobal, a formerly public company, was taken private by Vision Holdings in 2014. The well-established chip designer says it powers 85% of premium smartphones.
            ######################
            输出:
            ("entity"{tuple_delimiter}TECHGLOBAL{tuple_delimiter}ORGANIZATION{tuple_delimiter}TechGlobal is a stock now listed on the Global Exchange which powers 85% of premium smartphones)
            {record_delimiter}
            ("entity"{tuple_delimiter}VISION HOLDINGS{tuple_delimiter}ORGANIZATION{tuple_delimiter}Vision Holdings is a firm that previously owned TechGlobal)
            {record_delimiter}
            ("relationship"{tuple_delimiter}TECHGLOBAL{tuple_delimiter}VISION HOLDINGS{tuple_delimiter}Vision Holdings formerly owned TechGlobal from 2014 until present{tuple_delimiter}5)
            {completion_delimiter}
                        
            ######################
            示例 3:
            Entity_types: ORGANIZATION,LOCATION,PERSON
            文本:
            Five Aurelians jailed for 8 years in Firuzabad and widely regarded as hostages are on their way home to Aurelia.
                        
            The swap orchestrated by Quintara was finalized when $8bn of Firuzi funds were transferred to financial institutions in Krohaara, the capital of Quintara.
                        
            The exchange initiated in Firuzabad's capital, Tiruzia, led to the four men and one woman, who are also Firuzi nationals, boarding a chartered flight to Krohaara.
                        
            They were welcomed by senior Aurelian officials and are now on their way to Aurelia's capital, Cashion.
                        
            The Aurelians include 39-year-old businessman Samuel Namara, who has been held in Tiruzia's Alhamia Prison, as well as journalist Durke Bataglani, 59, and environmentalist Meggie Tazbah, 53, who also holds Bratinas nationality.
            ######################
            输出:
            ("entity"{tuple_delimiter}FIRUZABAD{tuple_delimiter}LOCATION{tuple_delimiter}Firuzabad held Aurelians as hostages)
            {record_delimiter}
            ("entity"{tuple_delimiter}AURELIA{tuple_delimiter}LOCATION{tuple_delimiter}Country seeking to release hostages)
            {record_delimiter}
            ("entity"{tuple_delimiter}QUINTARA{tuple_delimiter}LOCATION{tuple_delimiter}Country that negotiated a swap of money in exchange for hostages)
            {record_delimiter}
            {record_delimiter}
            ("entity"{tuple_delimiter}TIRUZIA{tuple_delimiter}LOCATION{tuple_delimiter}Capital of Firuzabad where the Aurelians were being held)
            {record_delimiter}
            ("entity"{tuple_delimiter}KROHAARA{tuple_delimiter}LOCATION{tuple_delimiter}Capital city in Quintara)
            {record_delimiter}
            ("entity"{tuple_delimiter}CASHION{tuple_delimiter}LOCATION{tuple_delimiter}Capital city in Aurelia)
            {record_delimiter}
            ("entity"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}PERSON{tuple_delimiter}Aurelian who spent time in Tiruzia's Alhamia Prison)
            {record_delimiter}
            ("entity"{tuple_delimiter}ALHAMIA PRISON{tuple_delimiter}LOCATION{tuple_delimiter}Prison in Tiruzia)
            {record_delimiter}
            ("entity"{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}PERSON{tuple_delimiter}Aurelian journalist who was held hostage)
            {record_delimiter}
            ("entity"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}PERSON{tuple_delimiter}Bratinas national and environmentalist who was held hostage)
            {record_delimiter}
            ("relationship"{tuple_delimiter}FIRUZABAD{tuple_delimiter}AURELIA{tuple_delimiter}Firuzabad negotiated a hostage exchange with Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}QUINTARA{tuple_delimiter}AURELIA{tuple_delimiter}Quintara brokered the hostage exchange between Firuzabad and Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}QUINTARA{tuple_delimiter}FIRUZABAD{tuple_delimiter}Quintara brokered the hostage exchange between Firuzabad and Aurelia{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}ALHAMIA PRISON{tuple_delimiter}Samuel Namara was a prisoner at Alhamia prison{tuple_delimiter}8)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}Samuel Namara and Meggie Tazbah were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}Samuel Namara and Durke Bataglani were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}Meggie Tazbah and Durke Bataglani were exchanged in the same hostage release{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}SAMUEL NAMARA{tuple_delimiter}FIRUZABAD{tuple_delimiter}Samuel Namara was a hostage in Firuzabad{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}MEGGIE TAZBAH{tuple_delimiter}FIRUZABAD{tuple_delimiter}Meggie Tazbah was a hostage in Firuzabad{tuple_delimiter}2)
            {record_delimiter}
            ("relationship"{tuple_delimiter}DURKE BATAGLANI{tuple_delimiter}FIRUZABAD{tuple_delimiter}Durke Bataglani was a hostage in Firuzabad{tuple_delimiter}2)
            {completion_delimiter}
                        
            ######################
            -真实数据-
            ######################
            Entity_types: {entity_types}
            文本: {input_text}
            ######################
            输出:
            """.replace("{tuple_delimiter}", ZhiMeshConstant.GRAPH_TUPLE_DELIMITER)
            .replace("{entity_types}", String.join(",", ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES))
            .replace("{completion_delimiter}", ZhiMeshConstant.GRAPH_COMPLETION_DELIMITER)
            .replace("{record_delimiter}", ZhiMeshConstant.GRAPH_RECORD_DELIMITER);
    public static final String CONTINUE_PROMPT = "MANY entities and relationships were missed in the last extraction. Remember to ONLY emit entities that match any of the previously extracted types. Add them below using the same format:\n";
    public static final String LOOP_PROMPT = "It appears some entities and relationships may have still been missed. Answer Y if there are still entities or relationships that need to be added, or N if there are none. Please answer with a single letter Y or N.\n";
}
