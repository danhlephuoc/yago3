package extractors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javatools.administrative.Announce;
import javatools.administrative.D;
import javatools.datatypes.FinalMap;
import javatools.filehandlers.FileLines;
import javatools.parsers.Char;
import javatools.util.FileUtils;
import basics.Fact;
import basics.FactCollection;
import basics.FactComponent;
import basics.FactSource;
import basics.FactWriter;
import basics.RDFS;
import basics.Theme;
import basics.YAGO;
import extractorUtils.PatternList;
import extractorUtils.TermExtractor;
import extractorUtils.TitleExtractor;

public class InfoboxExtractor extends Extractor {

	/** Input file */
	protected File wikipedia;

	@Override
	public Set<Theme> input() {
		return new HashSet<Theme>(Arrays.asList(PatternHardExtractor.INFOBOXPATTERNS,
				PatternHardExtractor.CATEGORYPATTERNS, WordnetExtractor.WORDNETWORDS,
				PatternHardExtractor.TITLEPATTERNS, HardExtractor.HARDWIREDFACTS));
	}

	/** Infobox facts, non-checked */
	public static final Theme DIRTYINFOBOXFACTS = new Theme("dirtyInfoxboxFacts");
	/** Types derived from infoboxes */
	public static final Theme INFOBOXTYPES = new Theme("infoboxTypes");

	@Override
	public Map<Theme, String> output() {
		return new FinalMap<Theme, String>(DIRTYINFOBOXFACTS,
				"Facts extracted from the Wikipedia infoboxes - still to be type-checked", INFOBOXTYPES,
				"Types extracted from Wikipedia infoboxes");
	}

	/** normalizes an attribute name */
	public static String normalizeAttribute(String a) {
		return (a.trim().toLowerCase().replace("_", "").replace(" ", "").replaceAll("\\d", ""));
	}

	/** Extracts a relation from a string */
	protected void extract(String entity, String string, String relation, Map<String, String> preferredMeanings,
			FactCollection factCollection, FactWriter n4Writer, PatternList replacements)
			throws IOException {
		string=replacements.transform(string);
		string = string.trim();
		if (string.length() == 0)
			return;
		
		// Check inverse
		boolean inverse;
		String cls;
		if (relation.endsWith("->")) {
			inverse = true;
			relation = Char.cutLast(Char.cutLast(relation))+'>';
			cls = factCollection.getArg2(relation, "rdfs:domain");
		} else {
			inverse = false;
			cls = factCollection.getArg2(relation, "rdfs:range");
		}
		if (cls == null) {
			Announce.warning("Unknown relation to extract:", relation);
			cls = "rdfs:Resource";
		}
		
		// Get the term extractor
		TermExtractor extractor = cls.equals(RDFS.clss) ? new TermExtractor.ForClass(preferredMeanings)
				: TermExtractor.forType(cls);
		Announce.debug("Relation", relation, "with type checker", extractor.getClass().getSimpleName());
		String syntaxChecker = factCollection.getArg2(cls, "<_hasTypeCheckPattern>");
		if (syntaxChecker != null)
			syntaxChecker = FactComponent.asJavaString(syntaxChecker);
		
		// Extract all terms
		List<String> objects=extractor.extractList(string);
		for (String object : objects) {
			if (syntaxChecker != null && !FactComponent.asJavaString(object).matches(syntaxChecker)) {
				Announce.debug("Extraction", object, "does not match typecheck", syntaxChecker);
				continue;
			}
			FactComponent.setDataType(object, cls);
			if (inverse)
				n4Writer.write(new Fact(object, relation, entity));
			else
				n4Writer.write(new Fact(entity, relation, object));
			if (factCollection.contains(relation, RDFS.type, YAGO.function))
				break;
		}
	}

	/** reads an environment, returns the char on which we finish */
	public static int readEnvironment(Reader in, StringBuilder b) throws IOException {
		final int MAX=4000;
		while (true) {
			if(b.length()>MAX) return(-2);
			int c;
			switch (c = in.read()) {
			case -1:
				return (-1);
			case '}':
				return (c);
			case '{':
				in.read();
				b.append("{{");
				while (c != -1 && c != '}') {
					b.append((char) c);
					c = readEnvironment(in, b);
					if(c==-2) return(-2);
				}
				in.read();
				b.append("}}");
				break;
			case '[':
				in.read();
				b.append("[[");
				while (c != -1 && c != ']') {
					b.append((char) c);
					c = readEnvironment(in, b);
					if(c==-2) return(-2);
				}
				in.read();
				b.append("]]");
				break;
			case ']':
				return (']');
			case '|':
				return ('|');
			default:
				b.append((char) c);
			}
		}
	}

	/** reads an infobox */
	public static Map<String, String> readInfobox(Reader in) throws IOException {
		Map<String, String> result = new TreeMap<String, String>();
		while (true) {
			String attribute = normalizeAttribute(FileLines.readTo(in, '=', '}').toString());
			if (attribute.length() == 0)
				return (result);
			StringBuilder value = new StringBuilder();
			int c = readEnvironment(in, value);
			result.put(attribute, value.toString());
			if (c == '}' || c == -1 || c==-2)
				break;
		}
		return (result);
	}

	@Override
	public void extract(Map<Theme, FactWriter> writers, Map<Theme, FactSource> input) throws Exception {
		FactCollection infoboxFacts=new FactCollection(input.get(PatternHardExtractor.INFOBOXPATTERNS));
		FactCollection hardWiredFacts=new FactCollection(input.get(HardExtractor.HARDWIREDFACTS));		
		Map<String, Set<String>> patterns = infoboxPatterns(infoboxFacts);
		PatternList replacements = new PatternList(infoboxFacts,
				"<_infoboxReplace>");
		Map<String, String> preferredMeaning = WordnetExtractor.preferredMeanings(hardWiredFacts, new FactCollection(input.get(WordnetExtractor.WORDNETWORDS)));
		TitleExtractor titleExtractor = new TitleExtractor(input);

		// Extract the information
		Announce.progressStart("Extracting",4_500_000);
		Reader in = FileUtils.getBufferedUTF8Reader(wikipedia);
		String titleEntity = null;
		while (true) {
			switch (FileLines.findIgnoreCase(in, "<title>", "{{Infobox", "{{ Infobox")) {
			case -1:
				Announce.progressDone();
				in.close();
				return;
			case 0:
				Announce.progressStep();
				titleEntity = titleExtractor.getTitleEntity(in);
				break;
			default:
				if (titleEntity == null)
					continue;
				String cls = FileLines.readTo(in, '}', '|').toString().trim();
				String type = preferredMeaning.get(cls);
				if (type != null) {
					writers.get(INFOBOXTYPES).write(new Fact(null, titleEntity, RDFS.type, type));
				}
				Map<String, String> attributes = readInfobox(in);
				for (String attribute : attributes.keySet()) {
					Set<String> relations = patterns.get(attribute);
					if (relations == null)
						continue;
					for (String relation : relations) {
						extract(titleEntity, attributes.get(attribute), relation, preferredMeaning, hardWiredFacts, writers.get(DIRTYINFOBOXFACTS), replacements);
					}
				}
			}
		}
	}

	/** returns the infobox patterns*/
	public static Map<String, Set<String>> infoboxPatterns(FactCollection infoboxFacts) {
		Map<String, Set<String>> patterns = new HashMap<String, Set<String>>();
		Announce.doing("Compiling infobox patterns");
		for (Fact fact :infoboxFacts.get("<_infoboxPattern>")) {
			D.addKeyValue(patterns, normalizeAttribute(fact.getArgJavaString(1)), fact.getArg(2), TreeSet.class);
		}
		if (patterns.isEmpty()) {
			Announce.warning("No infobox patterns found");
		}
		Announce.done();
		return (patterns);
	}

	/** Constructor from source file */
	public InfoboxExtractor(File wikipedia) {
		this.wikipedia = wikipedia;
	}

	public static void main(String[] args) throws Exception {
		Announce.setLevel(Announce.Level.DEBUG);
		new PatternHardExtractor(new File("./data")).extract(new File("c:/fabian/data/yago2s"), "test");
		new HardExtractor(new File("../basics2s/data")).extract(new File("c:/fabian/data/yago2s"), "test");
		new InfoboxExtractor(new File("./testCases/wikitest.xml")).extract(new File("c:/fabian/data/yago2s"), "test");
		//new InfoboxExtractor(new File("./testCases/wikitest.xml")).extract(new File("/Users/Fabian/Fabian/work/yago2/newfacts"), "test");
	}
}
