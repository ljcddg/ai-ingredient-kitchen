-- ============================================================
-- migration-10：生成记录补「知识库」与「依据校验」两组字段
-- ============================================================
-- 【为什么要落库，而不是只打日志】
-- 依据校验（grounding）产出的 faithfulness，就是 RAGAS 口径里的 Answer Faithfulness。
-- 它是本项目"生成结果到底可不可信"唯一可量化的指标 —— 只打日志的话，
-- 想回答"加了知识库之后有据比例涨了多少"就必须翻日志文本，做不了分组统计。
--
-- 三个计数分开存而不是只存一个比例：
--   grounded   用量落在库内参考 0.5~2 倍区间
--   deviated   有参照但偏离（做汤/炖菜可能合理，需要人工抽样判断）
--   ungrounded 库内没有这道菜用到它（新菜的正常代价；占比过高 = 生成脱离检索）
-- 只存 faithfulness 的话，0.5 到底是"一半有据"还是"一半瞎写"就分不出来了。
--
-- knowledge_rule_ids 记录本次注入了哪几条烹饪常识（knowledge/cooking-rules.json 的 id）。
-- 有了它才能回答"哪条知识真的有用"——按 rule id 分组比对 faithfulness。
--
-- ⚠️ faith 为 NULL 表示"判不了"（没参考 / 参考没带用量），**不是 0 分**。
--    统计时必须 WHERE faith IS NOT NULL，否则冷启动的数据会把均值拖垮。
-- ============================================================

USE project0914;

ALTER TABLE t_ai_generation_record
    ADD COLUMN grounded_cnt   INT          NULL COMMENT '有据项数（用量落在参考 0.5~2 倍）' AFTER reference_dish_ids,
    ADD COLUMN deviated_cnt   INT          NULL COMMENT '偏离项数（有参照但用量差得远）' AFTER grounded_cnt,
    ADD COLUMN ungrounded_cnt INT          NULL COMMENT '无据项数（库内无参照）' AFTER deviated_cnt,
    ADD COLUMN faith          DECIMAL(5,4) NULL COMMENT '有据比例 = grounded/(g+d+u)；NULL=判不了，不是 0' AFTER ungrounded_cnt,
    ADD COLUMN knowledge_rule_ids VARCHAR(128) NULL COMMENT '本次注入的烹饪常识条目 id，逗号分隔' AFTER reference_dish_ids;

-- 校验
DESC t_ai_generation_record;
