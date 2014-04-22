/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package wordnetsuggester;

import gate.*;
import gate.creole.*;
import gate.creole.metadata.*;
import gate.util.*;

import gate.Annotation;
import gate.AnnotationSet;
import gate.Document;
import gate.Factory;
import gate.FeatureMap;

import gate.wordnet.*;

import java.util.*;
import java.io.*;
import java.net.*;

import org.apache.commons.codec.language.Soundex;

/**
 *
 * @author victor
 * This class was edited to add concatenated annotations for further
 * indexing and retrieving purposes
 */
@CreoleResource(name = "WordNet Suggester Concatenated",
helpURL = "",
comment = "Plugin that wraps the WordNet API to add synonyms and hyponyms to features in the input/output annotations defined.")
public class WordNetSuggester extends AbstractLanguageAnalyser implements
        ProcessingResource,
        Serializable {

    private WordNet wordNet;        // WordNet instance
    private URL configFileURL;      // URL to WordNet configuration file
    
    private String inputASName;     //  Input AnnotationSet name
    private String outputASName;    // Output AnnotationSet set name
    private ArrayList<String> inputASTypes; // list of input annotations from which string content will be taken for input to WordNet
    private String outputASType;    // Output annotation name within outputASName for holding WordNet features
    private String inputASTypeFeature;      // Name of feature within inputASTypes from which string content will be submitted to WordNet
    private String tokASName;     //  Name of AnnotationSet containing Tokens
    private String tokName;         //  Name of Token annotation (default to Token)
    private String tokRoot;         //  Root or string content of Token (default to string)
    private String tokCategory;     //  POS category of Token (default to category)
    private Integer shortestWord;       // ignore words under this threshold
    private OutputFormat outputListFormat;      // Output Lists as strings or as a List object
    // Exit gracefully if exception caught on init()
    private boolean gracefulExit;
    private Integer truncateSize;               // truncate candidates lists to N size
    private boolean createNewAnnot;        // create a new annot to hold WordNet output
    private AnnotationSet outputAS;         // output AnnotationSet
    private Soundex m_soundex;

    // Output Lists as strings or as a List object
    public enum OutputFormat {
        String, List
    }

    @Override
    public Resource init() throws ResourceInstantiationException {
        gracefulExit = false;

        // Default input to WordNet is by Token
        inputASTypes = new ArrayList<String>();
        inputASTypes.add("Token");

        m_soundex = new Soundex();
        // If there is an instance of the WordNet LR already loaded, use that,
        // otherwise, load a new instance of the WordNet LR
        try {
            Gate.getCreoleRegister().registerDirectories(
              new File(Gate.getGateHome().getAbsolutePath()
                      + "/plugins/WordNet").toURI().toURL());
            try {
                wordNet = (gate.wordnet.WordNet) Gate.getCreoleRegister().getAllInstances("gate.wordnet.WordNet").get(0);
            } catch (IndexOutOfBoundsException i) {
                FeatureMap fm = Factory.newFeatureMap();
                fm.put("propertyUrl", configFileURL);
                wordNet = (WordNet)gate.Factory.createResource("gate.wordnet.JWNLWordNetImpl", fm);
            }
        } catch (MalformedURLException m) {
            gate.util.Err.println("Unable to locate WordNet plugin. Please check that it is installed.");
            gracefulExit = true;
        } catch (GateException g) {
            gate.util.Err.println("Unable to initialise WordNet plugin. Please check that it is installed and configured correctly.");
            gracefulExit = true;
        } 

        return this;
    } // end init()


    
    @Override
    public void execute() throws ExecutionException {
        // quit if setup failed
        if (gracefulExit) {
            gate.util.Err.println("Plugin was not initialised correctly. Exiting gracefully ... ");
            cleanup();
            fireProcessFinished();
            return;
        }

        // lookup the whole term first, if no results, then lookup individual tokens within the word
        AnnotationSet inputAS = (inputASName == null || inputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(inputASName);
        outputAS = (outputASName == null || outputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(outputASName);

        // Get all Tokens that are words
        FeatureMap tokFeats = Factory.newFeatureMap();

        tokFeats.put("kind", "word");
        AnnotationSet tokenAS = (tokASName == null || tokASName.trim().length() == 0) ? document.getAnnotations().get(tokName, tokFeats) : document.getAnnotations(tokASName).get(tokName, tokFeats);

        String docContent = document.getContent().toString();

        createNewAnnot = (outputASType == null || outputASType.isEmpty()) ? false : true;

        // process the content of each annot in inputASTypes
        for (String inputAnnName : inputASTypes) {

            AnnotationSet inputAnnSet = inputAS.get(inputAnnName);

            for (Annotation ann : inputAnnSet) {
                Long annStart = ann.getStartNode().getOffset();
                Long annEnd = ann.getEndNode().getOffset();

                List<Annotation> innerToks = new ArrayList<Annotation>(tokenAS.getContained(annStart, annEnd));
                Collections.sort(innerToks, new OffsetComparator());

                String strTerm = "";            // Annotation string content

                Object o = ann.getFeatures().get(inputASTypeFeature);
                String annFeatureContent = (o == null) ? "" : o.toString();

                // Use the content of a named feature as input ?
                if (annFeatureContent == null || annFeatureContent.trim().length() == 0) {
                    strTerm = docContent.substring(annStart.intValue(), annEnd.intValue()).trim();
                } else {
                    strTerm = annFeatureContent.trim();
                }

                // Skip processing for short words
                if (strTerm.length() < shortestWord) {
                    continue;
                }

                // Try to match the whole phrase, and treat it as a noun
                // if no match, then match individual tokens
                try {
                    boolean fullMatch = false;
                    // Attempt to match the whole phrase if it's not a Token or has not been tokenized
                    if (!inputAnnName.equals(tokName) && (innerToks.isEmpty() || innerToks.size() > 1)) {
                        fullMatch = wordNetSuggest(strTerm, ann, WordNet.POS_NOUN);
                    }

                    if (!fullMatch) {
                        for (Annotation tok : innerToks) {
                            Object oStr = tok.getFeatures().get(tokRoot);
                            strTerm = (oStr == null) ? "" : oStr.toString();
                            wordNetSuggest(strTerm, tok);
                        }
                    }

                } catch (WordNetException w) {
                    gate.util.Err.println(w.getMessage());
                }
            }
        }
        fireProcessFinished();
    } // end execute()


    
    /**
     * 
     * @param strTerm       The text to be looked up
     * @param ann           The annotation that spans the text
     * @return              True if matched, false if not
     * @throws WordNetException
     */
    private boolean wordNetSuggest(String strTerm, Annotation ann) throws WordNetException {
        FeatureMap fm = ann.getFeatures();
        int pos = WordNet.POS_NOUN;
        String posFeat = "NN";

        // Does it contain POS information
        if (fm.containsKey(tokCategory)) {
            posFeat = fm.get(tokCategory).toString();
        }

        if (posFeat.startsWith("NN")) {
            pos = WordNet.POS_NOUN;
        } else if (posFeat.startsWith("JJ")) {
            pos = WordNet.POS_ADJECTIVE;
        } else if (posFeat.startsWith("VB")) {
            pos = WordNet.POS_VERB;
        } else if (posFeat.startsWith("RB")) {
            pos = WordNet.POS_ADVERB;
        }

        return wordNetSuggest(strTerm, ann, pos);
    }

    /**
     * 
     * @param strTerm               The text to be looked up
     * @param ann                   The annotation that spans the text
     * @param pos                   POS identifier
     * @return                      True if matched, false if not
     * @throws WordNetException
     */
    private boolean wordNetSuggest(String strTerm1, Annotation ann, int pos) throws WordNetException {
        // Replace spaces with underscore so compound terms get matched if possible
        String strTerm = strTerm1.replaceAll("[\\s\\xA0]+", "_");
        FeatureMap fm = ann.getFeatures();

        this.addPhoneticFeature(fm, strTerm);
        
        List<WordSense> senseList = wordNet.lookupWord(strTerm, pos);
        if (senseList == null || senseList.isEmpty()) {
            return false;
        }

        int iter = 0;
        for (WordSense sense : senseList) {
            // Create a new FeatureMap for each new annot
            if (createNewAnnot) { fm = Factory.newFeatureMap(); }

            // only iterate over N candidates as specified by truncateSize parameter
            if (++iter > truncateSize) { break; }
            
            Synset s = sense.getSynset();

            if (createNewAnnot) {
                outputAS.add(ann.getStartNode(), ann.getEndNode(), outputASType, fm);
            } else if (iter > 1) {
                // If we're not creating new annotations,
                // we can only output features for the first candidate
                break;
            }

            fm.put("gloss", s.getGloss());

            List<WordSense> synonyms = s.getWordSenses();

            // Get synonyms and similar words
            List<String> synList = new ArrayList<String>();
            for (WordSense ws : synonyms) {
                synList.add(ws.getWord().getLemma());
            }

            // Adjectives have a similar-to relation, source of other synonyms
            if (pos == WordNet.POS_ADJECTIVE || sense.getPOS() == WordNet.POS_ADJECTIVE) {
                synList.addAll(getSemanticRelation(s, SemanticRelation.REL_SIMILAR_TO));
            }
            this.addFeatureConcateneted("synonyms", fm, synList);
            synList.clear();

            // Adjectives have a related noun
            if (pos == WordNet.POS_ADJECTIVE || sense.getPOS() == WordNet.POS_ADJECTIVE) {
                synList.addAll(getLexicalRelation(sense, LexicalRelation.REL_DERIVED_FROM_ADJECTIVE));
                this.addFeature("derived", fm, synList);
                synList.clear();
            }

            // Verbs have related verb group
            if (pos == WordNet.POS_VERB || sense.getPOS() == WordNet.POS_VERB) {
                synList.addAll(getSemanticRelation(s, SemanticRelation.REL_VERB_GROUP));
                this.addFeature("verb_group", fm, synList);
                synList.clear();
            }

            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_ANTONYM));
            synList.addAll(getLexicalRelation(sense, LexicalRelation.REL_ANTONYM));
            this.addFeatureConcateneted("antonyms", fm, synList);
            synList.clear();

            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_HYPERNYM));
            this.addFeatureConcateneted("hypernyms", fm, synList);
            synList.clear();

            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_HYPONYM));
            this.addFeatureConcateneted("hyponyms", fm, synList);
            synList.clear();

            // For simplicity, we don't distinguish between has_part, has_member, has_substance
            // - just use has_part
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_PART_MERONYM));
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_MEMBER_MERONYM));
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_SUBSTANCE_MERONYM));
            this.addFeatureConcateneted("meronyms", fm, synList);
            synList.clear();

            // For simplicity, we don't distinguish between part_of, member_of, substance_of
            // - just use part_of
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_PART_HOLONYM));
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_MEMBER_HOLONYM));
            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_SUBSTANCE_HOLONYM));
            this.addFeatureConcateneted("holonyms", fm, synList);
            synList.clear();

            synList.addAll(getSemanticRelation(s, SemanticRelation.REL_ATTRIBUTE));
            this.addFeature("attributes", fm, synList);
            synList.clear();

        }

        return true;
    }

    
    /**
     * 
     * @param feat              String feature name
     * @param fm                FeatureMap to which WordNet feature will be added
     * @param synList           List<String> to add to FeatureMap
     */
    private void addFeature(String feat, FeatureMap fm, List<String> synList) {
        if (!synList.isEmpty()) {
            if (outputListFormat == OutputFormat.List) {
                fm.put(feat, new ArrayList<String>(synList));
            } else {
                fm.put(feat, synList.toString());
            }
        }
    }
    
    /**
     * Adds the members of a particular semantic set in a concatenated manner
     * This way indexing and querying with GATE gets easier. Will be further used with ANNIC adaptation
     * @param feat is the current feature being updated
     * @param fm is the feature map
     * @param synList is the list of member for calling member semantic set
     */
    private void addFeatureConcateneted(String feat, FeatureMap fm, List<String> synList) {
        if (!synList.isEmpty()) {
            
        	String concatenated_value = "";
        	for (Iterator iterator = synList.iterator(); iterator.hasNext();) {
				String current_member = (String) iterator.next();
				concatenated_value = concatenated_value + current_member;
			}
        	
        	fm.put(feat, concatenated_value);
        	
        }
    }

    private void addPhoneticFeature(FeatureMap fm, String word)
    {
    	if (m_soundex != null && word != null)
    	{
    		try{
    			fm.put("phonetic", m_soundex.encode(word));	
    		}
    		catch(IllegalArgumentException e)
    		{
    			return;
    		}
    		
    	}
    	else
    	{
    		fm.put("phonetic", "NO_SOUND");
    	}
    }

    /**
     * 
     *
     * @param sense                 WordSense object
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getLexicalRelation(WordSense sense) throws WordNetException {
        List<LexicalRelation> lrList = sense.getLexicalRelations();
        return getLexicalRelation(lrList);
    }

    /**
     *
     * @param sense                 WordSense object
     * @param type                  LexicalRelation type
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getLexicalRelation(WordSense sense, int type) throws WordNetException {
        List<LexicalRelation> lrList = sense.getLexicalRelations(type);
        return getLexicalRelation(lrList);
    }

    /**
     *
     * @param lrList                List<LexicalRelation>
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getLexicalRelation(List<LexicalRelation> lrList) throws WordNetException {
        List<String> ret = new ArrayList<String>();
        int iter = 0;
        for (LexicalRelation lr : lrList) {
            if (++iter > truncateSize) { break; }
            // System.out.println(lr.getLabel() + ":" + lr.getType());
            WordSense target = lr.getTarget();
            Synset t = target.getSynset();
            List<WordSense> srTargetList = t.getWordSenses();
            for (WordSense targWs : srTargetList) {
                ret.add(targWs.getWord().getLemma());
            }
        }
        return ret;
    }

    /**
     *
     * @param s                     WordNet SynSet
     * @param type                  POS type
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getSemanticRelation(Synset s, int type) throws WordNetException {
        List<SemanticRelation> semrel = s.getSemanticRelations(type);
        return getSemanticRelation(semrel);
    }

    /**
     *
     * @param s                     WordNet SynSet
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getSemanticRelation(Synset s) throws WordNetException {
        List<SemanticRelation> semrel = s.getSemanticRelations();
        return getSemanticRelation(semrel);
    }

    /**
     *
     * @param semrel                List<SemanticRelation>
     * @return                      List<String> of related lemmas
     * @throws WordNetException
     */
    private List<String> getSemanticRelation(List<SemanticRelation> semrel) throws WordNetException {
        List<String> ret = new ArrayList<String>();
        int iter1 = 0;
        int iter2 = 0;
        for (SemanticRelation sr : semrel) {
            if (++iter1 > truncateSize) { break; }
            // System.out.println(sr.getLabel() + ":" + sr.getType());
            Synset target = sr.getTarget();
            List<WordSense> srTargetList = target.getWordSenses();
            for (WordSense targWs : srTargetList) {
                if (++iter2 > truncateSize) { break; }
                ret.add(targWs.getWord().getLemma());
            }
        }
        return ret;
    }


    @CreoleParameter(defaultValue = "resources/wordnet-config.xml",
    comment = "Location of configuration file")
    public void setConfigFileURL(URL configFileURL) {
        this.configFileURL = configFileURL;
    }

    public URL getConfigFileURL() {
        return configFileURL;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, only send the content of the given feature from annotations within inputASTypes to WordNet")
    public void setInputASTypeFeature(String inputASTypeFeature) {
        this.inputASTypeFeature = inputASTypeFeature;
    }

    public String getInputASTypeFeature() {
        return inputASTypeFeature;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Input Annotation Set Name")
    public void setInputASName(String inputASName) {
        this.inputASName = inputASName;
    }

    public String getInputASName() {
        return inputASName;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Output Annotation Set Name")
    public void setOutputASName(String outputASName) {
        this.outputASName = outputASName;
    }

    public String getOutputASName() {
        return outputASName;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, only send the content of the given Annotations in the input Annotation Set to WordNet")
    public void setInputASTypes(ArrayList<String> inputASTypes) {
        this.inputASTypes = inputASTypes;
    }

    public ArrayList<String> getInputASTypes() {
        return inputASTypes;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Write WordNet output to a new annotation")
    public void setOutputASType(String outputASType) {
        this.outputASType = outputASType;
    }

    public String getOutputASType() {
        return outputASType;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Name of AnnotationSet containing Tokens")
    public void setTokASName(String tokASName) {
        this.tokASName = tokASName;
    }

    public String getTokASName() {
        return tokASName;
    }

    @RunTime
    @CreoleParameter(defaultValue = "Token",
    comment = "Name of Token annotation")
    public void setTokName(String tokName) {
        this.tokName = tokName;
    }

    public String getTokName() {
        return tokName;
    }

    @RunTime
    @CreoleParameter(defaultValue = "string",
    comment = "Feature holding the Token string or root value")
    public void setTokRoot(String tokRoot) {
        this.tokRoot = tokRoot;
    }

    public String getTokRoot() {
        return tokRoot;
    }

    @RunTime
    @CreoleParameter(defaultValue = "category",
    comment = "Feature holding Token POS vaue")
    public void setTokCategory(String tokCategory) {
        this.tokCategory = tokCategory;
    }

    public String getTokCategory() {
        return tokCategory;
    }

    @RunTime
    @CreoleParameter(defaultValue = "4",
    comment = "Minimum word length to trigger a WordNet lookup")
    public void setShortestWord(Integer shortestWord) {
        this.shortestWord = shortestWord;
    }

    public Integer getShortestWord() {
        return shortestWord;
    }

    @RunTime
    @CreoleParameter(defaultValue = "String",
    comment = "Output lists as a string or as a List object")
    public void setOutputListFormat(OutputFormat outputListFormat) {
        this.outputListFormat = outputListFormat;
    }

    public OutputFormat getOutputListFormat() {
        return outputListFormat;
    }

    @RunTime
    @CreoleParameter(defaultValue = "4",
    comment = "Return the top N candidates")
    public void setTruncateSize(Integer truncateSize) {
        this.truncateSize = truncateSize;
    }

    public Integer getTruncateSize() {
        return truncateSize;
    }
}
