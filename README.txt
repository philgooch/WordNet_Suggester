WordNet Suggester 
=================================

This plugin wraps the GATE WordNet API to automate the addition of WordNet features (synonyms, hypernyms, antonyms) to input annotations.

You must first ensure that WordNet is installed on your system and that the GATE WordNet plugin is configured correctly.

If you wish to process input annotations other than Tokens, the plugin will first attempt to match against the whole phrase wrapped by the annotation (for example, 'dark horse'). If no match is found, then individual Tokens within the input annotation will be matched.

Parameters
==========

- Init-time
-----------
configFileURL: path to the WordNet configuration file. The example file provided assumes that you have WordNet 3.0 installed in /usr/local/WordNet-3.0


- Run-time
----------------

inputASName: Input AnnotationSet name. Optional, leave blank for default annotation set.

inputASTypeFeature: Name of the feature on inputASTypes from which to extract strings for input to WordNet. Optional, leave blank to use the string content of inputASTypes.

inputASTypes: List of input annotations from which to extract strings for input to the spell-checker. Default is Token.

outputASName: Output AnnotationSet name. Optional. Only used if outputASType is set.

outputASType: Create new annotations with this name, to hold the WordNet output. Optional.

outputListFormat: Set to 'String' so that WordNet ArrayList<String> output can be matched with JAPE LHS expressions. Set to 'List' so that WordNet output can be iterated over with JAPE RHS expressions.

shortestWord: Ignore words shorter than N. Default is 4.

tokASName: AnnotationSet containing Tokens. Leave blank for default annotation set.

tokName: Token annotation name. Defaults to Token.

tokRoot: Name of the feature containing the Token root. Defaults to 'root'.

tokCategory: Name of the feature containing the Token POS information. Defaults to 'category'.

truncateSize: Only return the top N candidates. Default is 4.