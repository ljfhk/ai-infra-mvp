package com.inspection.service;

import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * 巡检报告生成服务
 * 风格：商务简约（方案一）+ 安全高亮（方案三）
 */
@Service
public class ReportService {

    // ===== 颜色常量 =====
    /** 表头深蓝背景 */
    private static final String COLOR_HEADER_BG   = "1A56A0";
    /** 偶数行浅灰背景（斑马纹） */
    private static final String COLOR_ROW_EVEN    = "F4F6F9";
    /** 边框线颜色（浅灰） */
    private static final String COLOR_BORDER      = "D1D8E0";
    /** 标签列字体色（深灰） */
    private static final String COLOR_LABEL       = "374151";
    /** 普通值字体色 */
    private static final String COLOR_VALUE       = "111827";
    /** 风险高亮背景（浅橙） */
    private static final String COLOR_RISK_BG     = "FFF3CD";
    /** 风险字体色（橙红） */
    private static final String COLOR_RISK_TEXT   = "C0392B";
    /** 正常状态字体色（绿） */
    private static final String COLOR_OK_TEXT     = "1E8449";

    public byte[] generateReport(Map<String, Object> record) throws Exception {
        XWPFDocument doc = new XWPFDocument();

        // ── 页边距 ──────────────────────────────────────────
        CTSectPr sectPr = doc.getDocument().getBody().addNewSectPr();
        CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setLeft(BigInteger.valueOf(1080));    // ~1.9cm
        pageMar.setRight(BigInteger.valueOf(1080));
        pageMar.setTop(BigInteger.valueOf(1200));
        pageMar.setBottom(BigInteger.valueOf(1200));

        // ── 主标题 ──────────────────────────────────────────
        addMainTitle(doc, "AI Infra 诊断报告");
        addEmptyLine(doc);

        // ── 基本信息 ─────────────────────────────────────────
        addSectionTitle(doc, "一、基本信息");
        String[] infoLabels  = {"巡检时间", "IP 地址", "主机名", "操作系统", "巡检状态"};
        String scanTime = nvl(record.get("scan_time"));
        // 格式化时间（去掉T和毫秒）
        if (scanTime.contains("T")) {
            scanTime = scanTime.replace("T", " ");
            if (scanTime.contains(".")) scanTime = scanTime.substring(0, scanTime.indexOf('.'));
        }
        String statusRaw = nvl(record.get("status"));
        String[] infoValues = {
            scanTime,
            nvl(record.get("ip")),
            nvl(record.get("hostname")),
            nvl(record.get("os_info")),
            "SUCCESS".equals(statusRaw) ? "✓ 成功" : "✗ 失败"
        };
        createStyledTable(doc, infoLabels, infoValues, null);
        addEmptyLine(doc);

        // ── CPU 信息 ──────────────────────────────────────────
        addSectionTitle(doc, "二、CPU 信息");
        String cpuInfo = nvl(record.get("cpu_info"));
        if (!cpuInfo.isEmpty() && cpuInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> cpu = om.readValue(cpuInfo, Map.class);
                String cpuPct = nvl(cpu.get("usage_percent")).replace("%","");
                String[] labels = {"型号", "核心数", "使用率"};
                String[] values = {
                    nvl(cpu.get("model")),
                    nvl(cpu.get("cores")),
                    formatPercent(cpuPct)
                };
                createStyledTable(doc, labels, values, null);
            } catch (Exception e) {
                doc.createParagraph().createRun().setText(cpuInfo);
            }
        }
        addEmptyLine(doc);

        // ── 内存信息 ──────────────────────────────────────────
        addSectionTitle(doc, "三、内存信息");
        String memInfo = nvl(record.get("mem_info"));
        if (!memInfo.isEmpty() && memInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> mem = om.readValue(memInfo, Map.class);
                String memPct = nvl(mem.get("usage_percent")).replace("%","");
                String[] labels = {"总内存", "已用", "空闲", "使用率"};
                String[] values = {
                    nvl(mem.get("total")),
                    nvl(mem.get("used")),
                    nvl(mem.get("free")),
                    formatPercent(memPct)
                };
                createStyledTable(doc, labels, values, null);
            } catch (Exception e) {
                doc.createParagraph().createRun().setText(memInfo);
            }
        }
        addEmptyLine(doc);

        // ── 磁盘信息 ──────────────────────────────────────────
        addSectionTitle(doc, "四、磁盘信息");
        String diskInfo = nvl(record.get("disk_info"));
        if (!diskInfo.isEmpty() && diskInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> disk = om.readValue(diskInfo, Map.class);
                // 处理 usage 字段（可能带%，也可能是数字）
                String usageRaw = nvl(disk.get("usage") != null ? disk.get("usage") : disk.get("usage_percent"));
                String usagePct = usageRaw.replace("%","").trim();
                String[] labels = {"总容量", "已用", "使用率"};
                String[] values = {
                    nvl(disk.get("total")),
                    nvl(disk.get("used")),
                    formatPercent(usagePct)
                };
                createStyledTable(doc, labels, values, null);
            } catch (Exception e) {
                doc.createParagraph().createRun().setText(diskInfo);
            }
        }
        addEmptyLine(doc);

        // ── 安全信息 ──────────────────────────────────────────
        addSectionTitle(doc, "五、安全信息");
        String secInfo = nvl(record.get("security_info"));
        if (!secInfo.isEmpty() && secInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> sec = om.readValue(secInfo, Map.class);
                String selinux  = nvl(sec.get("selinux"));
                String firewall = nvl(sec.get("firewall"));
                String sshd     = nvl(sec.get("sshd"));
                String rootSsh  = nvl(sec.get("root_ssh_login"));

                String[] labels = {"SELinux", "防火墙", "SSH 服务", "Root SSH 登录"};
                // 清洗重复拼接的数据（如 inactiveunknown → inactive, yesyes → yes）
                String fwClean   = cleanValue(firewall);
                String rootClean = cleanValue(rootSsh);
                String[] values = {
                    selinux,
                    fwClean,
                    sshd,
                    "yes".equalsIgnoreCase(rootClean) ? "允许（存在风险）" : ("no".equalsIgnoreCase(rootClean) ? "禁止" : (rootClean.isEmpty() ? "-" : rootClean))
                };
                // 风险标记：true=风险行，false=正常行，null=中性
                Boolean[] risks = {
                    isSelinuxRisk(selinux),
                    isFirewallRisk(fwClean),
                    isServiceRisk(sshd),
                    "yes".equalsIgnoreCase(rootClean) ? Boolean.TRUE : Boolean.FALSE
                };
                createStyledTable(doc, labels, values, risks);
            } catch (Exception e) {
                doc.createParagraph().createRun().setText(secInfo);
            }
        }

                // ── 巡检结论与建讨（智能分析）──────────
        addConclusionSection(doc, record);

// ── 报告页脚 ──────────────────────────────────────────
        addEmptyLine(doc);
        XWPFParagraph divider = doc.createParagraph();
        addHorizontalRule(divider);

        XWPFParagraph footer = doc.createParagraph();
        footer.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun fr = footer.createRun();
        fr.setFontSize(9);
        fr.setColor("8A9BAC");
        fr.setItalic(true);
        fr.setText("报告生成时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.write(bos);
        doc.close();
        return bos.toByteArray();
    }

    // ===== 主标题 =====

    /**
     * 智能生成巡检结论与建议
     * 根据实际检测数据，输出针对性的运维建议
     */
    private void addConclusionSection(XWPFDocument doc, Map<String, Object> record) {
        addSectionTitle(doc, "六、巡检结论与建议");

        java.util.List<String> advices = new java.util.ArrayList<>();

        // ---- 磁盘使用率分析 ----
        String diskInfo = nvl(record.get("disk_info"));
        if (!diskInfo.isEmpty() && diskInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> disk = om.readValue(diskInfo, Map.class);
                String usageRaw = nvl(disk.get("usage") != null ? disk.get("usage") : disk.get("usage_percent"));
                String usageStr = usageRaw.replaceAll("[^-0-9.]", "").trim();
                double diskPct = Double.parseDouble(usageStr);
                if (diskPct >= 80) {
                    advices.add(String.format(
                        "检测到磁盘 %s 使用率已达 %.1f%%（高从在险），建议及时清理旧记、临时文件或考虑扩容。",
                        nvl(disk.get("mount")), diskPct));
                } else if (diskPct >= 60) {
                    advices.add(String.format(
                        "检测到磁盘 %s 使用率为 %.1f%%（接近阈值），建议定期检查并清理无用数据。",
                        nvl(disk.get("mount")), diskPct));
                }
            } catch (Exception ignored) {}
        }

        // ---- CPU 使用率分析 ----
        String cpuInfo = nvl(record.get("cpu_info"));
        if (!cpuInfo.isEmpty() && cpuInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> cpu = om.readValue(cpuInfo, Map.class);
                double cpuPct = Double.parseDouble(nvl(cpu.get("usage_percent")).replaceAll("[^-0-9.]",""));
                if (cpuPct >= 80) {
                    advices.add(String.format(
                        "检测到 CPU 使用率达 %.1f%%（载荷较高），建议排查高负载进程并考虑优化。",
                        cpuPct));
                }
            } catch (Exception ignored) {}
        }

        // ---- 内存使用率分析 ----
        String memInfo = nvl(record.get("mem_info"));
        if (!memInfo.isEmpty() && memInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> mem = om.readValue(memInfo, Map.class);
                double memPct = Double.parseDouble(nvl(mem.get("usage_percent")).replaceAll("[^-0-9.]",""));
                if (memPct >= 85) {
                    advices.add(String.format(
                        "检测到内存使用率达 %.1f%%（紧张），建议检查是否有内存泄漏或考虑增加内存。",
                        memPct));
                }
            } catch (Exception ignored) {}
        }

        // ---- 安全状态分析 ----
        String secInfo = nvl(record.get("security_info"));
        if (!secInfo.isEmpty() && secInfo.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> sec = om.readValue(secInfo, Map.class);
                String selinux  = cleanValue(nvl(sec.get("selinux")));
                String firewall = cleanValue(nvl(sec.get("firewall")));
                String rootSsh  = cleanValue(nvl(sec.get("root_ssh_login")));

                // SELinux
                if (isSelinuxRisk(selinux)) {
                    advices.add("检测到 SELinux 处于 " + selinux + " 状态，不符合等保2.0要求，建议配置为 enforcing 模式。");
                }
                // 防火墙
                if (isFirewallRisk(firewall)) {
                    advices.add("检测到防火墙处于 " + firewall + " 状态，存在安全风险，建议启用防火墙并配置规则。");
                }
                // Root SSH
                if ("yes".equalsIgnoreCase(rootSsh)) {
                    advices.add("检测到 Root 直接 SSH 登录已开启，存在被暴力破解风险，建议禁止 Root 直接登录或配置密钥认证。");
                }
            } catch (Exception ignored) {}
        }

        // ---- 总体评价 ----
        XWPFParagraph summaryPara = doc.createParagraph();
        summaryPara.setSpacingBefore(100);
        summaryPara.setSpacingAfter(100);
        summaryPara.setIndentationLeft(200);
        XWPFRun summaryRun = summaryPara.createRun();
        summaryRun.setFontSize(10);
        summaryRun.setFontFamily("微软雅黑");
        summaryRun.setColor(COLOR_VALUE);

        if (advices.isEmpty()) {
            summaryRun.setText(
                "本次巡检未发现明显风险项。" +
                "CPU、内存、磁盘使用率均在合理范围内，" +
                "安全配置符合基本要求。建议定期执行巡检以及时发现潜在问题。");
        } else {
            summaryRun.setText("本次巡检发现 " + advices.size() +
                " 项需要关注的问题，详细建议如下：");
        }

        // ---- 建议列表 ----
        for (int i = 0; i < advices.size(); i++) {
            XWPFParagraph itemPara = doc.createParagraph();
            itemPara.setSpacingBefore(60);
            itemPara.setSpacingAfter(60);
            itemPara.setIndentationLeft(400);

            XWPFRun idxRun = itemPara.createRun();
            idxRun.setFontSize(10);
            idxRun.setFontFamily("微软雅黑");
            idxRun.setColor(COLOR_RISK_TEXT);
            idxRun.setBold(true);
            idxRun.setText((i + 1) + ". ");

            XWPFRun textRun = itemPara.createRun();
            textRun.setFontSize(10);
            textRun.setFontFamily("微软雅黑");
            textRun.setColor(COLOR_VALUE);
            textRun.setText(advices.get(i));
        }

        // ---- 免责声明 ----
        addEmptyLine(doc);
        XWPFParagraph discPara = doc.createParagraph();
        discPara.setAlignment(ParagraphAlignment.LEFT);
        discPara.setIndentationLeft(200);
        XWPFRun discRun = discPara.createRun();
        discRun.setFontSize(8);
        discRun.setFontFamily("微软雅黑");
        discRun.setColor("999999");
        discRun.setItalic(true);
        discRun.setText(
            "注：本建议基于当前巡检数据自动生成，" +
            "具体操作请结合实际业务场景评估后执行。");
    }


    private void addMainTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingAfter(200);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(22);
        r.setFontFamily("黑体");
        r.setColor(COLOR_LABEL);
        r.setText(text);
    }

    // ===== 二级节标题 =====
    private void addSectionTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(120);
        p.setSpacingAfter(80);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(12);
        r.setFontFamily("微软雅黑");
        r.setColor("1A56A0");
        r.setText(text);
        // 节标题下方加细线
        addHorizontalRule(p);
    }

    // ===== 空行 =====
    private void addEmptyLine(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(0);
        p.setSpacingBefore(0);
        p.createRun().setText("");
    }

    // ===== 分割线（段落底部边框） =====
    private void addHorizontalRule(XWPFParagraph p) {
        CTPPr pPr = (p.getCTP().getPPr() != null) ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTPBdr pBdr = (pPr.getPBdr() != null) ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setColor(COLOR_BORDER);
        bottom.setSz(BigInteger.valueOf(4));
        bottom.setSpace(BigInteger.valueOf(4));
    }

    /**
     * 创建商务简约风格双列表格
     * @param labels  标签列内容
     * @param values  值列内容
     * @param risks   null=不做高亮；true=风险行；false=正常高亮；null元素=中性
     */
    private void createStyledTable(XWPFDocument doc,
                                   String[] labels,
                                   String[] values,
                                   Boolean[] risks) {
        int rows = labels.length;
        XWPFTable table = doc.createTable(rows, 2);

        // 去掉默认的四周粗边框，改成细灰线
        CTTbl tbl = table.getCTTbl();
        CTTblPr tblPr = (tbl.getTblPr() != null) ? tbl.getTblPr() : tbl.addNewTblPr();

        // 表格宽度 100%
        CTTblWidth tblW = (tblPr.getTblW() != null) ? tblPr.getTblW() : tblPr.addNewTblW();
        tblW.setType(STTblWidth.PCT);
        tblW.setW(BigInteger.valueOf(5000)); // 100% in fiftieths

        // 统一边框
        CTTblBorders tblBorders = (tblPr.getTblBorders() != null) ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        applyTblBorder(tblBorders.addNewTop(),    COLOR_BORDER, 4);
        applyTblBorder(tblBorders.addNewBottom(), COLOR_BORDER, 4);
        applyTblBorder(tblBorders.addNewLeft(),   COLOR_BORDER, 4);
        applyTblBorder(tblBorders.addNewRight(),  COLOR_BORDER, 4);
        applyTblBorder(tblBorders.addNewInsideH(), COLOR_BORDER, 4);
        // 去掉竖分隔线（方案一核心）
        CTBorder insideV = tblBorders.addNewInsideV();
        insideV.setVal(STBorder.NONE);

        // 每行填充内容
        for (int i = 0; i < rows; i++) {
            XWPFTableRow row = table.getRow(i);
            boolean isEvenRow = (i % 2 == 1); // 0-based，奇数index=偶数行

            // 确定行背景色
            String rowBg = null;
            boolean isRisk = (risks != null && risks[i] != null && Boolean.TRUE.equals(risks[i]));
            boolean isOk   = (risks != null && risks[i] != null && Boolean.FALSE.equals(risks[i]));

            if (isRisk) {
                rowBg = COLOR_RISK_BG;
            } else if (isEvenRow) {
                rowBg = COLOR_ROW_EVEN;
            }

            // 标签单元格（约占 28%，约4.8cm）
            XWPFTableCell labelCell = row.getCell(0);
            setCellWidthPct(labelCell, 2800); // 百分比宽度（fiftieths of %）
            if (rowBg != null) setCellBgColor(labelCell, rowBg);
            XWPFParagraph labelPara = labelCell.getParagraphs().get(0);
            labelPara.setSpacingBefore(60);
            labelPara.setSpacingAfter(60);
            XWPFRun labelRun = labelPara.createRun();
            labelRun.setBold(true);
            labelRun.setFontSize(10);
            labelRun.setFontFamily("微软雅黑");
            labelRun.setColor(COLOR_LABEL);
            labelRun.setText(labels[i]);

            // 值单元格（占剩余 ~72%）
            XWPFTableCell valueCell = row.getCell(1);
            setCellWidthPct(valueCell, 7200);
            if (rowBg != null) setCellBgColor(valueCell, rowBg);
            XWPFParagraph valuePara = valueCell.getParagraphs().get(0);
            valuePara.setSpacingBefore(60);
            valuePara.setSpacingAfter(60);
            XWPFRun valueRun = valuePara.createRun();
            valueRun.setFontSize(10);
            valueRun.setFontFamily("微软雅黑");

            // 安全状态着色（方案三）
            if (isRisk) {
                valueRun.setColor(COLOR_RISK_TEXT);
                valueRun.setBold(true);
            } else if (isOk) {
                valueRun.setColor(COLOR_OK_TEXT);
            } else {
                valueRun.setColor(COLOR_VALUE);
            }
            valueRun.setText(values[i]);
        }
    }

    // ===== 工具方法 =====

    private void applyTblBorder(CTBorder border, String color, int sz) {
        border.setVal(STBorder.SINGLE);
        border.setColor(color);
        border.setSz(BigInteger.valueOf(sz));
        border.setSpace(BigInteger.valueOf(0));
    }

    private void setCellWidth(XWPFTableCell cell, int width) {
        CTTcPr tcPr = (cell.getCTTc().getTcPr() != null) ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth w = (tcPr.getTcW() != null) ? tcPr.getTcW() : tcPr.addNewTcW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(width * 10L));
    }

    /** 设置单元格宽度为百分比（参数单位：fiftieths of a percent，如 2800 表示 56%） */
    private void setCellWidthPct(XWPFTableCell cell, int pct) {
        CTTcPr tcPr = (cell.getCTTc().getTcPr() != null) ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth w = (tcPr.getTcW() != null) ? tcPr.getTcW() : tcPr.addNewTcW();
        w.setType(STTblWidth.PCT);
        w.setW(BigInteger.valueOf(pct));
    }

    private void setCellBgColor(XWPFTableCell cell, String hexColor) {
        CTTcPr tcPr = (cell.getCTTc().getTcPr() != null) ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = (tcPr.getShd() != null) ? tcPr.getShd() : tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(hexColor);
    }

    private String formatPercent(String pctStr) {
        try {
            double v = Double.parseDouble(pctStr.trim());
            String bar = buildBar(v);
            return String.format("%.1f%%  %s", v, bar);
        } catch (Exception e) {
            return pctStr + "%";
        }
    }

    /** 构建文字进度条（Unicode 方块字符 ■□）*/
    private String buildBar(double pct) {
        int filled = (int) Math.round(pct / 10);
        if (filled < 0) filled = 0;
        if (filled > 10) filled = 10;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '■' : '□');
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean isSelinuxRisk(String v) {
        // disabled / Permissive 都算风险
        return "disabled".equalsIgnoreCase(v) || "permissive".equalsIgnoreCase(v);
    }

    private boolean isFirewallRisk(String v) {
        // 不包含 active 算风险
        return v != null && !v.toLowerCase().contains("active");
    }

    private boolean isServiceRisk(String v) {
        // sshd 不 active 算风险
        return v != null && !"active".equalsIgnoreCase(v.trim());
    }

    /** 清洗重复拼接的值（如 inactiveunknown → inactive, yesyes → yes, unknownunknown → unknown） */
    private String cleanValue(String raw) {
        if (raw == null || raw.isEmpty() || "-".equals(raw)) return "";
        String s = raw.trim();
        // 常见状态关键词列表（按长度降序排列，优先匹配长的）
        String[] KEYWORDS = {"disabled", "enabled", "inactive", "active", "unknown",
                             "permissive", "enforcing", "running", "stopped", "failed"};
        // 先尝试完全相同的重复（yesyes → yes, unknownunknown → unknown）
        int len = s.length();
        for (int split = 1; split <= len / 2; split++) {
            String part1 = s.substring(0, len - split);
            String part2 = s.substring(len - split);
            if (part1.endsWith(part2) && part2.length() >= 2) {
                return part1;
            }
        }
        // 再尝试前缀重复（yesyes 的另一种检测方式）
        for (int split = len / 2; split >= 2; split--) {
            String part1 = s.substring(0, split);
            String part2 = s.substring(split);
            if (part2.startsWith(part1)) {
                return part1;
            }
        }
        // 最后：从字符串开头匹配已知关键词，取最长匹配（处理 inactiveunknown → inactive）
        String lower = s.toLowerCase();
        for (String kw : KEYWORDS) {
            if (lower.startsWith(kw)) {
                // 确认后面还有其他内容（说明是拼接）
                if (lower.length() > kw.length()) {
                    return s.substring(0, kw.length());
                }
            }
        }
        return s;
    }

    private String nvl(Object obj) {
        if (obj == null) return "-";
        String s = String.valueOf(obj).trim();
        return s.isEmpty() ? "-" : s;
    }
}
