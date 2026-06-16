package net.deckserver.tournament.compare;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ArchonXlsxImporter {

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public SeatingPlan importPlan(Path xlsxPath, int playerCount, int preliminaryRounds) throws IOException {
        if (playerCount < 4) {
            throw new IllegalArgumentException("Archon seating starts at 4 players.");
        }
        if (preliminaryRounds != 2 && preliminaryRounds != 3) {
            throw new IllegalArgumentException("Archon supports 2R+F and 3R+F seating charts.");
        }

        try (ZipFile zip = new ZipFile(xlsxPath.toFile())) {
            List<String> sharedStrings = readSharedStrings(zip);
            Map<String, String> sheets = readSheetPaths(zip);
            String sheetName = preliminaryRounds == 3 ? "Optimal Seating 3R+F" : "Optimal Seating 2R+F";
            String sheetPath = sheets.get(sheetName);
            if (sheetPath == null) {
                throw new IllegalArgumentException("Workbook does not contain sheet: " + sheetName);
            }
            Map<String, String> cells = readCells(zip, sheetPath, sharedStrings);
            int blockStartRow = 8 + (playerCount - 4) * 5;
            List<SeatingRound> rounds = new ArrayList<>();
            for (int roundNumber = 1; roundNumber <= preliminaryRounds; roundNumber++) {
                List<Integer> rowValues = readRoundRow(cells, blockStartRow + roundNumber - 1);
                if (rowValues.isEmpty()) {
                    throw new IllegalArgumentException("No Archon seating for " + playerCount
                            + " players, round " + roundNumber + " in " + sheetName);
                }
                rounds.add(toRound(playerCount, roundNumber, rowValues));
            }
            SeatingPlan plan = new SeatingPlan(playerCount, preliminaryRounds, rounds,
                    "Archon " + sheetName + " " + xlsxPath);
            new TimefoldPlanMapper().validate(plan);
            return plan;
        }
    }

    private SeatingRound toRound(int playerCount, int roundNumber, List<Integer> rowValues) {
        List<SeatingTable> tables = new ArrayList<>();
        Set<Integer> seated = new HashSet<>();
        int tableNumber = 1;
        for (int i = 0; i < rowValues.size(); i += 5) {
            List<Integer> players = new ArrayList<>();
            for (int j = i; j < Math.min(i + 5, rowValues.size()); j++) {
                Integer player = rowValues.get(j);
                if (player != null) {
                    players.add(player);
                    seated.add(player);
                }
            }
            if (!players.isEmpty()) {
                tables.add(new SeatingTable(tableNumber++, List.copyOf(players)));
            }
        }

        Set<Integer> sittingOut = new TreeSet<>();
        for (int player = 1; player <= playerCount; player++) {
            if (!seated.contains(player)) {
                sittingOut.add(player);
            }
        }
        return new SeatingRound(roundNumber, List.copyOf(tables), sittingOut);
    }

    private List<Integer> readRoundRow(Map<String, String> cells, int row) {
        List<Integer> values = new ArrayList<>();
        int lastNonEmptyIndex = -1;
        int maxColumn = columnNumber("KS");
        for (int column = columnNumber("F"); column <= maxColumn; column++) {
            String value = cells.get(cellRef(row, column));
            Integer parsed = parseInteger(value);
            values.add(parsed);
            if (parsed != null) {
                lastNonEmptyIndex = values.size() - 1;
            }
        }
        if (lastNonEmptyIndex < 0) {
            return List.of();
        }
        return new ArrayList<>(values.subList(0, lastNonEmptyIndex + 1));
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed == Math.rint(parsed)) {
                return (int) parsed;
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> readCells(ZipFile zip, String sheetPath, List<String> sharedStrings) throws IOException {
        Document document = parse(zip, sheetPath);
        Map<String, String> cells = new HashMap<>();
        NodeList cellNodes = document.getElementsByTagNameNS(MAIN_NS, "c");
        for (int i = 0; i < cellNodes.getLength(); i++) {
            Element cell = (Element) cellNodes.item(i);
            String ref = cell.getAttribute("r");
            String type = cell.getAttribute("t");
            Element valueElement = firstChild(cell, MAIN_NS, "v");
            if (valueElement == null) {
                continue;
            }
            String value = valueElement.getTextContent();
            if ("s".equals(type)) {
                value = sharedStrings.get(Integer.parseInt(value));
            }
            cells.put(ref, value);
        }
        return cells;
    }

    private Map<String, String> readSheetPaths(ZipFile zip) throws IOException {
        Document workbook = parse(zip, "xl/workbook.xml");
        Document rels = parse(zip, "xl/_rels/workbook.xml.rels");
        Map<String, String> relById = new HashMap<>();
        NodeList relNodes = rels.getDocumentElement().getElementsByTagName("Relationship");
        for (int i = 0; i < relNodes.getLength(); i++) {
            Element rel = (Element) relNodes.item(i);
            relById.put(rel.getAttribute("Id"), "xl/" + rel.getAttribute("Target"));
        }

        Map<String, String> sheetPaths = new HashMap<>();
        NodeList sheetNodes = workbook.getElementsByTagNameNS(MAIN_NS, "sheet");
        for (int i = 0; i < sheetNodes.getLength(); i++) {
            Element sheet = (Element) sheetNodes.item(i);
            String relId = sheet.getAttributeNS(REL_NS, "id");
            sheetPaths.put(sheet.getAttribute("name"), relById.get(relId));
        }
        return sheetPaths;
    }

    private List<String> readSharedStrings(ZipFile zip) throws IOException {
        if (zip.getEntry("xl/sharedStrings.xml") == null) {
            return List.of();
        }
        Document document = parse(zip, "xl/sharedStrings.xml");
        List<String> values = new ArrayList<>();
        NodeList stringNodes = document.getElementsByTagNameNS(MAIN_NS, "si");
        for (int i = 0; i < stringNodes.getLength(); i++) {
            Element stringNode = (Element) stringNodes.item(i);
            NodeList textNodes = stringNode.getElementsByTagNameNS(MAIN_NS, "t");
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < textNodes.getLength(); j++) {
                value.append(textNodes.item(j).getTextContent());
            }
            values.add(value.toString());
        }
        return values;
    }

    private Document parse(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            throw new IOException("Missing workbook entry: " + path);
        }
        try (InputStream inputStream = zip.getInputStream(entry)) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(inputStream);
        } catch (Exception e) {
            throw new IOException("Failed to parse workbook entry: " + path, e);
        }
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String cellRef(int row, int column) {
        return columnName(column) + row;
    }

    private static int columnNumber(String columnName) {
        int result = 0;
        for (int i = 0; i < columnName.length(); i++) {
            result = result * 26 + (columnName.charAt(i) - 'A' + 1);
        }
        return result;
    }

    private static String columnName(int columnNumber) {
        StringBuilder result = new StringBuilder();
        int current = columnNumber;
        while (current > 0) {
            current--;
            result.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return result.toString();
    }
}
