package dev.dota.etl.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalFightReportTest {
    @Test
    void personalReportUsesMemberStatisticsNotConcurrentKillsOrTeamEconomy() throws Exception {
        var metrics = new ObjectMapper().readTree("""
            {"local_fights":[
              {"id":0,"start":10,"end":12,"kind":"harassment","hero_damage":120,"scored_deaths":0,"unknown_deaths":0},
              {"id":1,"start":10,"end":12,"kind":"pickoff","hero_damage":9000,"scored_deaths":1,"unknown_deaths":0}],
             "local_fight_players":[
              {"fight_id":0,"hero_key":"owner","damage_dealt":120,"damage_taken":0,"kills":0,"deaths":0,"presence_events":0},
              {"fight_id":0,"hero_key":"victim","damage_dealt":0,"damage_taken":120,"kills":0,"deaths":0,"presence_events":2},
              {"fight_id":1,"hero_key":"remote","damage_dealt":9000,"damage_taken":0,"kills":1,"deaths":0,"presence_events":2}],
             "local_fight_events":[{"fight_id":null}],
             "kills":[{"t":11,"killer_key":"owner","victim_key":"remote"}],
             "teamfights":[{"start":10,"end":15,"economy":{"radiant":{"gold":999999}}}]}
            """);
        var sb = new StringBuilder();
        LocalFightReport.append(sb,metrics,"owner");
        String report=sb.toString();
        assertTrue(report.contains("owner 输出/承伤 120/0 击杀/死亡 0/0"));
        assertTrue(report.contains("|victim |owner"), "only victim has body presence evidence");
        assertFalse(report.contains("remote"));
        assertFalse(report.contains("999999"));
        assertFalse(report.contains("9000"));
        assertTrue(report.contains("全场未定位交互事件：1"));
        assertTrue(report.contains("经济因果归属未知"));
        assertTrue(report.contains("死亡后目标仍仅时间关联"));
        sb.setLength(0);
        LocalFightReport.append(sb,metrics,null);
        assertTrue(sb.toString().contains("remote"), "match report includes both independent fights");
    }
}
