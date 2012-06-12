package au.org.ala.checklist.lucene;

import au.org.ala.checklist.lucene.model.MatchType;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.FSDirectory;
import org.gbif.ecat.model.ParsedName;
import org.gbif.ecat.parser.NameParser;
import org.gbif.ecat.voc.NameType;
import au.org.ala.data.util.TaxonNameSoundEx;

import au.org.ala.checklist.lucene.model.NameSearchResult;
import au.org.ala.data.model.ALAParsedName;
import au.org.ala.data.model.LinnaeanRankClassification;
import au.org.ala.data.util.PhraseNameParser;
import au.org.ala.data.util.RankType;
import java.util.Set;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.util.Version;
import org.gbif.ecat.parser.UnparsableException;
import org.gbif.ecat.voc.Rank;

/**
 * The API used to perform a search on the CB Lucene Index.  It follows the following
 * algorithm when trying to find a match:
 *
 * 1. Search for a direct match for supplied name on the name field (with the optional rank provided).
 *
 * 2. Search for a match on the alternative name field (with optional rank)
 *
 * 3. Generate a searchable canonical name for the supplied name.  Search for a match on
 * the searchable canonical field using the generated name
 *
 * 4. Clean up the supplied name using the ECAT name parser. Repeat steps 1 to 3 on
 * the clean name until a match is found
 *
 * 5. No match is found
 *
 * When a match is found the existence of homonyms are checked.  Where a homonym exists,
 * if the kingdom of the result does not match the supplied kingdom a HomonymException is thrown.
 *
 * @author Natasha
 */
public class CBIndexSearch {
    protected Log log = LogFactory.getLog(CBIndexSearch.class);
    private IndexReader cbReader,irmngReader, vernReader;
    private IndexSearcher cbSearcher, irmngSearcher, vernSearcher, idSearcher;
    private ThreadLocal<QueryParser> queryParser;
    private ThreadLocal<QueryParser>idParser;
    protected TaxonNameSoundEx tnse;
    private PhraseNameParser parser;
    private static final String RANK_MARKER_ALL = "( "+StringUtils.join(Rank.RANK_MARKER_MAP.keySet(), "| ")+")\\.";
    private static final Pattern RANK_MARKER = Pattern.compile(RANK_MARKER_ALL);
    //public static final Set<String> stopWords = new java.util.HashSet<String>(java.util.Arrays.asList(new String[]{"virus", "ictv", "ICTV"}));
    public static final Pattern virusStopPattern = Pattern.compile(" virus| ictv| ICTV");
    public static final Pattern voucherRemovePattern = Pattern.compile(" |,|&|\\.");    
    /**
     * A set of names that are cross rank homonyms.
     */
    private Set crossRankHomonyms;

	public CBIndexSearch() {}

	/**
         * Creates a new name searcher. Using the indexDirectory
         * as the source directory
         *
         * @param indexDirectory The directory that contains the CB and IRMNG index.
         * @throws CorruptIndexException
         * @throws IOException
         */
        public CBIndexSearch(String indexDirectory) throws CorruptIndexException, IOException {
                //Initialis CB index searching items
            log.debug("Creating the search object for the name matching api...");
            //make the query parsers thread safe
            queryParser = new ThreadLocal<QueryParser>(){
               @Override
               protected QueryParser initialValue() {
                   return new QueryParser(Version.LUCENE_34, "genus", new au.org.ala.checklist.lucene.analyzer.LowerCaseKeywordAnalyzer());
               }
            };
            idParser = new ThreadLocal<QueryParser>(){
                @Override
                protected QueryParser initialValue() {
                    return new QueryParser(Version.LUCENE_34, "lsid", new org.apache.lucene.analysis.KeywordAnalyzer());
                }
            };


		cbReader = IndexReader.open(FSDirectory.open(createIfNotExist(indexDirectory+File.separator+"cb")), false);
		cbSearcher = new IndexSearcher(cbReader);
                //Initalise the IRMNG index searching items
                irmngReader = IndexReader.open(FSDirectory.open(createIfNotExist(indexDirectory+File.separator+"irmng")), true);
                irmngSearcher = new IndexSearcher(irmngReader);
                //initalise the Common name index searching items
                vernReader = IndexReader.open(FSDirectory.open(createIfNotExist(indexDirectory+File.separator+"vernacular")), true);
                vernSearcher = new IndexSearcher(vernReader);
                //initialise the identifier index
                idSearcher = new IndexSearcher(FSDirectory.open(createIfNotExist(indexDirectory + File.separator + "id")), true);
		tnse = new TaxonNameSoundEx();
		parser = new PhraseNameParser();
                crossRankHomonyms =au.org.ala.data.util.FileUtils.streamToSet(
                this.getClass().getClassLoader().getResourceAsStream("au/org/ala/homonyms/cross_rank_homonyms.txt"), new java.util.HashSet<String>(), true);
	}
        private File createIfNotExist(String indexDirectory) throws IOException{

            File idxFile = new File(indexDirectory);
		if(!idxFile.exists()){
			FileUtils.forceMkdir(idxFile);
			Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_29);
            IndexWriter iw = new IndexWriter(FSDirectory.open(idxFile), analyzer, MaxFieldLength.UNLIMITED);
            iw.commit();
            iw.close();
		}
            return idxFile;
        }

        public void reopenReaders(){
            //this should only need to reopen the cbSearcher because the others should NOT be changing
            try{
            IndexReader tmpReader = cbReader.reopen();
            if(tmpReader!=cbReader){
                cbReader.close();
                cbReader = tmpReader;
                //now reinit the searcher
                cbSearcher= new IndexSearcher(cbReader);
            }
            }
            catch(Exception e){

            }

        }

//        void deleteRecord(int id)throws Exception{
//            cbReader.deleteDocument(id);
//
//            cbReader.close();
//             IndexWriter iw = new IndexWriter(FSDirectory.open(new File("/data/lucene/namematching/cb")), new au.org.ala.checklist.lucene.analyzer.LowerCaseKeywordAnalyzer(), false, MaxFieldLength.UNLIMITED);
//            System.out.println("Optimizing...");
//             iw.optimize();
//            iw.close();
//        }
        /**
         * Run this class to Dump a list of species level LSID to file.
         * @param args
         */
//        public static void main(String[] args){
//            try{
//                CBIndexSearch searcher = new CBIndexSearch("/data/lucene/namematching");
//                //searcher.dumpSpecies();
//                searcher.deleteRecord(2324132);
//            }
//            catch(Exception e){
//                e.printStackTrace();
//            }
//        }
        /**
         * Dumps a list of the species LSID's that are contained in the index.
         */
        public void dumpSpecies(){
            try{
                OutputStreamWriter fileOut = new OutputStreamWriter(new FileOutputStream("/data/species.txt"), "UTF-8");
                Term term = new Term("rank", "species");
                TopDocs hits = cbSearcher.search(new TermQuery(term), 2000000);


            for(ScoreDoc sdoc : hits.scoreDocs){
                Document doc = cbReader.document(sdoc.doc);

                if(doc.getField("synonym") == null){
                    String lsid = StringUtils.trimToNull(doc.getField("lsid").stringValue());
                    if(lsid == null)
                        lsid = doc.getField("id").stringValue();
                    fileOut.write(lsid + "\n");
                }

            }
            fileOut.flush();

            }
            catch(Exception e){
                e.printStackTrace();
            }

        }

    /**
     * Searches the index for the supplied name with or without fuzzy name matching.
     * Returns null when there is no result
     * or the LSID for the first result. Where no LSID exist for the record the
     * CB ID is returned instead
     *
     * @throws HomonymException when an unresolved homonym is detected
     *
     * @param name
     * @param fuzzy look for a fuzzy match
     * @return
     */
    public String searchForLSID(String name, boolean fuzzy) throws SearchResultException{
        return searchForLSID(name, null, fuzzy);
    }
    /**
     * Searches for the name without using fuzzy name matching...
     *
     * @see #searchForLSID(java.lang.String, boolean)
     *
     * @param name scientific name for a taxon
     */
    public String searchForLSID(String name) throws SearchResultException{
        return searchForLSID(name, false);
    }
    /**
     * Searches the index for the supplied name of the specified rank with or without
     * fuzzy name matching.  Returns
     * null when there is no result or the LSID for the first result.
     * Where no LSID exist for the record the
     * CB ID is returned instead
     *
     * When the result is a synonym the "accepted" taxons's LSID is returned.
     *
     * @param name
     * @param rank
     * @param fuzzy look for a fuzzy match
     * @throws HomonymException when an unresolved homonym is detected
     * @return
     */
    public String searchForLSID(String name, RankType rank, boolean fuzzy)throws SearchResultException{
        return searchForLSID(name, null, rank, fuzzy);

    }
    /**
     * Searches for an LSID of the supplied name and rank without a fuzzy match...
     *
     * @see #searchForLSID(java.lang.String, au.org.ala.data.util.RankType, boolean)
     *
     * @param name
     * @param rank
     * @return
     * @throws SearchResultException
     */
    public String searchForLSID(String name, RankType rank) throws SearchResultException {
        return searchForLSID(name, null, rank, false);
    }
    /**
     * Searches for the LSID of the supplied name and rank. Using the kingdom to
     * resolve homonym issues.
     *
     *
     *
     * @param name
     * @param kingdom
     * @param genus
     * @param rank
     * @deprecated Use {@link #searchForLSID(java.lang.String, au.org.ala.data.model.LinnaeanRankClassification, au.org.ala.data.util.RankType, boolean)} instead.
     * It is more extensible to supply a classification object then a list of higher classification

     * @return
     * @throws SearchResultException
     */
    @Deprecated
    public String searchForLSID(String name, String kingdom, String scientificName, RankType rank) throws SearchResultException{
        LinnaeanRankClassification cl = new LinnaeanRankClassification(kingdom, scientificName);
        return searchForLSID(name, cl, rank, false);
    }
    /**
     * Search for an LSID based on the supplied name, classification and rank with or without fuzzy name matching.
     *
     * When a classification is supplied it is used for 2 purposes:
     * <ol>
     * <li> To try and resolve potential homonyms</li>
     * <li> To provide "optional" components to the search.  Thus an incorrect higher
     * classification will not prevent matches from occurring.</li>
     * </ol>
     * If it is not provided and a homonym is detected in the result a HomonymException is thrown.
     *
     *
     * @param name
     * @param cl The high taxa that form the classification for the search item
     * @param rank
     * @param fuzzy look for a fuzzy match
     * @return
     * @throws HomonymException  When an unresolved homonym is detected
     */
    public String searchForLSID(String name, LinnaeanRankClassification cl, RankType rank, boolean fuzzy) throws SearchResultException{
		String lsid = null;
    	NameSearchResult result = searchForRecord(name, cl, rank, fuzzy);
		if (result != null) {
			if (result.getAcceptedLsid()==null && result.getLsid()==null) {
				log.warn("LSID missing for [name=" + name + ", id=" + result.getId() + "]");
			} else {
				lsid = result.getAcceptedLsid()!= null ? result.getAcceptedLsid() :result.getLsid();
			}
		}
		return lsid;
    }

    /**
     * Search for an LSID with the supplied classification without a fuzzy match.
     * Supplying to classification in this way allows the API to try and ascertain the rank and
     * the correct scientific name to use.
     *
     * @param cl the classification to work with
     * @return An LSID for the taxon or null if nothing matched or homonym issues detected
     * @throws SearchResultException
     */
    public String searchForLSID(LinnaeanRankClassification cl, boolean recursiveMatching) throws SearchResultException {
        NameSearchResult nsr = searchForRecord(cl, recursiveMatching);
        if(nsr!=null){
        	return nsr.getLsid();
        }
        return null;
    }
    /**
     * Updates the supplied classification so that the supplied ID's are substituted with GUIDs.
     * @param cl
     */
    public void updateClassificationWithGUID(LinnaeanRankClassification cl){
        if(cl.getKid() != null){
            cl.setKid(searchForLsidById(cl.getKid()));
        }
        if(cl.getPid() != null)
            cl.setPid(searchForLsidById(cl.getPid()));
        if(cl.getCid() != null)
            cl.setCid(searchForLsidById(cl.getCid()));
        if(cl.getOid() != null)
            cl.setOid(searchForLsidById(cl.getOid()));
        if(cl.getFid() != null)
            cl.setFid(searchForLsidById(cl.getFid()));
        if(cl.getGid() != null)
            cl.setGid(searchForLsidById(cl.getGid()));
        if(cl.getSid() != null)
            cl.setSid(searchForLsidById(cl.getSid()));
    }
    public NameSearchResult searchForRecord(LinnaeanRankClassification cl, boolean recursiveMatching)throws SearchResultException{
        return searchForRecord(cl, recursiveMatching, false, false);
    }

    public NameSearchResult searchForRecord(LinnaeanRankClassification cl, boolean recursiveMatching, boolean fuzzy)throws SearchResultException{
        return searchForRecord(cl, recursiveMatching, false, fuzzy);
    }

    /**
     * Search for an LSID with the supplied classification without a fuzzy match.
     * Supplying to classification in this way allows the API to try and ascertain the rank and
     * the correct scientific name to use.
     *
     * @param cl the classification to work with
     * @param recursiveMatching whether to try matching to a higher taxon when leaf taxa matching fails
     * @return An LSID for the taxon or null if nothing matched or homonym issues detected
     * @throws SearchResultException
     */
    public NameSearchResult searchForRecord(LinnaeanRankClassification cl, boolean recursiveMatching, boolean addGuids, boolean fuzzy) throws SearchResultException {

    	RankType rank = null;
    	String name = cl.getScientificName();
//        if(name.contains("?")){
//            name =name.replaceAll("\\?", "").replaceAll("\\[", "").replaceAll("]", "");
//
//            //Add warning that removed question marks...
//        }
    	NameSearchResult nsr = null;
        SearchResultException currentError = null;
    	if(name==null){
    		//ascertain the rank and construct the scientific name
            if (StringUtils.isNotEmpty(cl.getInfraspecificEpithet()) && !isInfraSpecificMarker(cl.getSubspecies())) {
                rank = RankType.SUBSPECIES;
                //construct the full scientific name from the parts
                if(StringUtils.isNotEmpty(cl.getGenus()) && StringUtils.isNotEmpty(cl.getSpecificEpithet())){
                	name = cl.getGenus() + " " + cl.getSpecificEpithet() + " " +cl.getInfraspecificEpithet();
                }
	        } else if (StringUtils.isNotEmpty(cl.getSubspecies()) && !isInfraSpecificMarker(cl.getSubspecies())) {
                rank = RankType.SUBSPECIES;
            	name = cl.getSubspecies();
	        } else if (StringUtils.isNotEmpty(cl.getSpecificEpithet()) && !isSpecificMarker(cl.getSpecies())) {
                rank = RankType.SPECIES;
                //construct the full scientific name from the parts
                if(StringUtils.isNotEmpty(cl.getGenus())){
                	name = cl.getGenus() + " " + cl.getSpecificEpithet() ;
                }
	        } else if (StringUtils.isNotEmpty(cl.getSpecies()) && !isSpecificMarker(cl.getSpecies())) {
                rank = RankType.SPECIES;
                //construct the full scientific name from the parts
            	name = cl.getSpecies();
                //check to see of the name is a binomial
                    if(!name.trim().contains(" ")){
                        //construct the bionomial
                        if(StringUtils.isNotEmpty(cl.getGenus())){
                            name = cl.getGenus() + " " + cl.getSpecificEpithet() ;
                        }
                        else{
                            name =null;
                        }
                    }
	        } else if (StringUtils.isNotEmpty(cl.getGenus())) {
                rank = RankType.GENUS;
                //construct the full scientific name from the parts
            	name = cl.getGenus();
	        } else if (StringUtils.isNotEmpty(cl.getFamily())) {
                rank = RankType.FAMILY;
                //construct the full scientific name from the parts
            	name = cl.getFamily();
	        } else if (StringUtils.isNotEmpty(cl.getOrder())) {
                rank = RankType.ORDER;
                //construct the full scientific name from the parts
            	name = cl.getOrder();
	        } else if (StringUtils.isNotEmpty(cl.getKlass())) {
                rank = RankType.CLASS;
                //construct the full scientific name from the parts
            	name = cl.getKlass();
	        } else if (StringUtils.isNotEmpty(cl.getPhylum())) {
                rank = RankType.PHYLUM;
                //construct the full scientific name from the parts
            	name = cl.getPhylum();
	        } else if (StringUtils.isNotEmpty(cl.getKingdom())) {
                rank = RankType.KINGDOM;
                //construct the full scientific name from the parts
            	name = cl.getKingdom();
	        }
           // nsr = searchForRecord(name, cl, rank, false);
    	} else {
                //check to see if the rank can be determined by matching the scentific name to one of values
                if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getSubspecies()))
                    rank = RankType.SUBSPECIES;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getSpecies()))
                    rank = RankType.SPECIES;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getGenus()))
                    rank = RankType.GENUS;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getFamily()))
                    rank = RankType.FAMILY;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getOrder()))
                    rank = RankType.ORDER;
                else if(rank == null && StringUtils.equalsIgnoreCase(name,  cl.getKlass()))
                    rank = RankType.CLASS;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getPhylum()))
                    rank = RankType.PHYLUM;
                else if(rank == null && StringUtils.equalsIgnoreCase(name, cl.getKingdom()))
                    rank = RankType.KINGDOM;


                if(rank == null){
                    if(recursiveMatching){
                        if(name.endsWith(" sp") || name.endsWith(" sp.")){
                            name = name.substring(0, name.lastIndexOf(" "));
                            cl.setGenus(name);
                        }
                    }
                    //check to see if the rank can be determined from the scientific name
                    try{
                    ParsedName<?> cn = parser.parse(name);
                    if(cn != null && cn.type == NameType.doubtful){
                        //if recursive set the issue
                        if(recursiveMatching){
                            name = cn.genusOrAbove;
//                            currentError = new SearchResultException("Questionable identification of species");
//                            currentError.setErrorType(au.org.ala.checklist.lucene.model.ErrorType.QUESTION_SPECIES);
                        }
                    }
                    if(cn!=null && cn.isBinomial()) {
                        //set the genus if it is empty
                            if(StringUtils.isEmpty(cl.getGenus()))
                                cl.setGenus(cn.genusOrAbove);
                        if(cn.rank == null && cn.cultivar == null && cn.isParsableType()){

                            if(cn.getInfraSpecificEpithet() != null){
                                rank = RankType.SUBSPECIES;
                                //populate the species if it is empty
                                if(StringUtils.isEmpty(cl.getSpecies()))
                                    cl.setSpecies(cn.genusOrAbove + " " + cn.specificEpithet);
                            }
                            else
                                rank = RankType.SPECIES;
                        }
                        else if(cn.cultivar != null){
                            rank = RankType.CULTIVAR;
                        }
                        else if(cn.rank != null){
                            // It is not necesary to update the rank based on rank markers at this point
                            // This is because it is done at the lowest level possible just before the search is performed

                        }
                    }
                    }
                    catch(org.gbif.ecat.parser.UnparsableException e){
                        //TODO log error maybe??
                    }
                }

    		//nsr = searchForRecord(name, cl, rank, false);
    	}
       
        try{
            nsr = searchForRecord(name, cl, rank, fuzzy);
        }
        catch(SearchResultException e){
            if(e instanceof HomonymException)
                throw e;
            else{
                currentError = e;
                nsr = null;
            }
        }

    	if(nsr==null && recursiveMatching){
                if(nsr == null && rank != RankType.SPECIES
                        && ((StringUtils.isNotEmpty(cl.getSpecificEpithet()) && !isSpecificMarker(cl.getSpecies())) ||
                        (StringUtils.isNotEmpty(cl.getSpecies()) && !isSpecificMarker(cl.getSpecies())))){
                    name = cl.getSpecies();
                    if(StringUtils.isEmpty(name))
                        name = cl.getGenus()+ " " + cl.getSpecificEpithet();
                    nsr = searchForRecord(name, cl, RankType.SPECIES, fuzzy);
                }
    		if(nsr == null && cl.getGenus()!=null){
    			nsr = searchForRecord(cl.getGenus(), cl, RankType.GENUS, fuzzy);
    		}
    		if(nsr == null && cl.getFamily()!=null){
    			nsr = searchForRecord(cl.getFamily(), cl, RankType.FAMILY, fuzzy);
    		}
    		if(nsr == null && cl.getOrder()!=null){
    			nsr = searchForRecord(cl.getOrder(), cl, RankType.ORDER, fuzzy);
    		}
    		if(nsr == null && cl.getKlass()!=null){
    			nsr = searchForRecord(cl.getKlass(), cl, RankType.CLASS, fuzzy);
    		}
    		if(nsr == null && cl.getPhylum()!=null){
    			nsr = searchForRecord(cl.getPhylum(), cl, RankType.PHYLUM, fuzzy);
    		}
    		if(nsr == null && cl.getKingdom()!=null){
    			nsr = searchForRecord(cl.getKingdom(), cl, RankType.KINGDOM, fuzzy);
    		}

            if (nsr != null)
                nsr.setMatchType(MatchType.RECURSIVE);
                
    	}
        //Obtain and store the GUIDs for the classification identifiers
        if (nsr != null && addGuids) {
            updateClassificationWithGUID(nsr.getRankClassification());
        }
        
            //set the error type so that the user knows that there was an error
//            if (currentError != null) {
//                nsr.setErrorType(currentError.getErrorType());
//            }
        
        if (nsr == null && currentError != null)
            throw currentError;
    	return nsr;
    }

    /**
     * FIXME need to include other types of marker
     *
     * @param subspecies
     * @return
     */
    private boolean isInfraSpecificMarker(String subspecies) {
    	String epithet = StringUtils.trimToNull(subspecies);
    	if(epithet!=null){
    		if("spp".equalsIgnoreCase(epithet) || "spp.".equalsIgnoreCase(epithet)) return true;
    	}
		return false;
	}

    /**
     * FIXME need to include other types of marker
     *
     * @param species
     * @return
     */
	private boolean isSpecificMarker(String species) {
    	String epithet = StringUtils.trimToNull(species);
    	if(epithet!=null){
    		if("sp".equalsIgnoreCase(epithet) || "sp.".equalsIgnoreCase(epithet)) return true;
    	}
		return false;
	}

    /**
     * Search for an LSID based on suppled name, classification and rank without a fuzzy match...
     * @param name
     * @param cl
     * @param rank
     * @return
     * @throws SearchResultException
     */
    public String searchForLSID(String name, LinnaeanRankClassification cl, RankType rank) throws SearchResultException{
        return searchForLSID(name, cl, rank, false);
    }
    /**
     * Searches the index for the supplied name of the specified rank.  Returns
     * null when there is no result or the result object for the first result.
     *
     * @param name
     * @param rank
     * @param fuzzy look for a fuzzy match
     * @return
     */
    public NameSearchResult searchForRecord(String name, RankType rank, boolean fuzzy) throws SearchResultException{
        return searchForRecord(name, null,  rank, fuzzy);
    }
    /**
     * Searches index for the supplied name and rank without a fuzzy match.
     * @param name
     * @param rank
     * @return
     * @throws SearchResultException
     */
    public NameSearchResult searchForRecord(String name, RankType rank) throws SearchResultException{
        return searchForRecord(name, rank, false);
    }
    /**
     * Searches for a record based on the supplied name and rank. It uses the kingdom and genus to resolve homonyms.
     *
     * @param name
     * @param kingdom
     * @param genus
     * @param rank
     * @deprecated Use {@link #searchForRecord(java.lang.String, au.org.ala.data.model.LinnaeanRankClassification, au.org.ala.data.util.RankType, boolean)} instead.
     * It is more extensible to supply a classification object then a list of higher classification
     * @return
     * @throws SearchResultException
     */
    @Deprecated
    public NameSearchResult searchForRecord(String name, String kingdom, String genus, RankType rank)throws SearchResultException{
        LinnaeanRankClassification cl = new LinnaeanRankClassification(kingdom, genus);
        return searchForRecord(name,cl, rank, false);
    }
    /**
     * Searches for a record based on the supplied name, rank and classification
     * with or without fuzzy name matching.
     * @param name
     * @param cl
     * @param rank
     * @param fuzzy
     * @return
     * @throws SearchResultException
     */
    public NameSearchResult searchForRecord(String name, LinnaeanRankClassification cl, RankType rank, boolean fuzzy)throws SearchResultException{
        //search for more than 1 term in case homonym resolution takes place at a lower level??
        List<NameSearchResult> results = searchForRecords(name, rank, cl, 10, fuzzy);
        if(results != null && results.size()>0)
            return results.get(0);

        return null;
    }
    /**
     * Searches for a record based on the supplied name, classification and rank without fuzzy name matching
     * @param name
     * @param cl
     * @param rank
     * @return
     * @throws SearchResultException
     */
    public NameSearchResult searchForRecord(String name, LinnaeanRankClassification cl, RankType rank) throws SearchResultException{
        return searchForRecord(name, cl, rank, false);
    }
    /**
     * Returns the records that has the supplied checklist bank id
     * @param id
     * @return
     */
    public NameSearchResult searchForRecordByID(String id){
        try{
            List<NameSearchResult> results = performSearch(CBCreateLuceneIndex.IndexField.ID.toString(),id, null,  null, 1, null, false, idParser.get());
            if(results.size()>0)
                return results.get(0);
        }
        catch(SearchResultException e){
            //this should not happen as we are  not checking for homonyms
            //homonyms should only be checked if a search is being performed by name
        }
        catch(IOException e){}
        return null;
    }
    /**
     * Gets the LSID for the record that has the supplied checklist bank id.
     * @param id
     * @return
     */
    public String searchForLsidById(String id){
        NameSearchResult result = searchForRecordByID(id);
        if(result != null)
            return result.getAcceptedLsid() != null ? result.getAcceptedLsid() : result.getLsid();
        return null;
    }
    /**
     * Searches for records with the specified name and rank with or without fuzzy name matching
     *
     * @param name
     * @param rank
     * @param fuzzy search for a fuzzy match
     * @return
     */
    public List<NameSearchResult> searchForRecords(String name, RankType rank, boolean fuzzy) throws SearchResultException{
        return searchForRecords(name, rank, null, 10, fuzzy);
    }
    /**
     * Searches for a list of results for the supplied name, classification and rank without fuzzy match
     *
     * @param name
     * @param rank
     * @param cl
     * @param max
     * @return
     * @throws SearchResultException
     */
    public List<NameSearchResult> searchForRecords(String name, RankType rank, LinnaeanRankClassification cl, int max) throws SearchResultException{
        return searchForRecords(name,rank, cl, max, false);
    }

    /**
     * Searches for the records that satisfy the given conditions using the algorithm
     * outlined in the class description.
     *
     * @param name
     * @param rank
     * @param kingdom
     * @param genus
     * @param max The maximum number of results to return
     * @param fuzzy search for a fuzzy match
     * @return
     * @throws SearchResultException
     */
    public List<NameSearchResult> searchForRecords(String name, RankType rank, LinnaeanRankClassification cl, int max, boolean fuzzy) throws SearchResultException{
        return searchForRecords(name, rank, cl, max, fuzzy, true);
    }

    /**
     * The new implementation for a name search as on December 2011.  It performs the following steps in an attempt to find a match:
     * <ol>
     * <li> Exact String Match of scientific name. </li>
     * <li> Canonical String Match when the parsed name is valid </li>
     * <li> Phrase Name, genus and optionally specificEpithet match when the name is determined to be a phrase </li>
     * <li> Sounds LIke match on genus, specific epithet and optionally infra specific epithet </li>
     * </ol>
     * @param name
     * @param rank
     * @param cl
     * @param max
     * @param fuzzy
     * @param clean
     * @return
     * @throws SearchResultException
     */
    private List<NameSearchResult> searchForRecords(String name, RankType rank, LinnaeanRankClassification cl, int max, boolean fuzzy, boolean clean) throws SearchResultException{
                //The name is not allowed to be null

       
        //Check for null name before attempting to do anything else
        if(name == null)
            throw new SearchResultException("Unable to perform search. Null value supplied for the name.");

        //remove all the "stop" words from the scientific name
        try{
            name = virusStopPattern.matcher(name).replaceAll(" ").trim();
        }
        catch(Exception e){
            log.error(e.getMessage(), e);
        }

        //According to http://en.wikipedia.org/wiki/Species spp. is used as follows:
        //The authors use "spp." as a short way of saying that something applies to many species within a genus,
        //but do not wish to say that it applies to all species within that genus.
        //Thus we don't want to attempt to match on spp.
        if(name.contains("spp."))
            throw new SPPException();//SearchResultException("Unable to perform search. Can not match to a subset of species within a genus.");

        try{
            //Check for the exact match
            List<NameSearchResult> hits = performSearch(NameIndexField.NAME.toString(), name, rank, cl, max, MatchType.EXACT, true, queryParser.get());
                if (hits == null) // situation where searcher has not been initialised
                    return null;
                if (hits.size() > 0)
                    return hits;

            //Parse the name and see what type of check to do next
            try{
                ParsedName<?> pn = parser.parse(name);

                //at this point we don't want to match informal names
                //if(pn.getType() == NameType.informal)
                //    throw new InformalNameException();
                
                if(pn instanceof ALAParsedName){
                    //check the phrase name
                    ALAParsedName alapn = (ALAParsedName)pn;
                    String genus = alapn.getGenusOrAbove();
                    String phrase = alapn.cleanPhrase;//alapn.getLocationPhraseDesciption();
                    String voucher = alapn.cleanVoucher;
                    //String voucher = alapn.phraseVoucher != null ? voucherRemovePattern.matcher(alapn.phraseVoucher).replaceAll("") :null;
                    String specific = alapn.rank != null && alapn.rank.equals("sp.")?null: alapn.specificEpithet;
                    String[][] searchFields = new String[4][];
                    searchFields[0] = new String[]{RankType.GENUS.getRank(),genus};
                    searchFields[1]= new String[]{NameIndexField.PHRASE.toString(), phrase};
                    searchFields[2] = new String[]{NameIndexField.VOUCHER.toString(), voucher};
                    searchFields[3] = new String[]{NameIndexField.SPECIFIC.toString(), specific};
                    hits = performSearch(searchFields, rank, cl, max, MatchType.PHRASE, false, queryParser.get()); //don't want to check for homonyms yet...
                    if(hits.size()>0)
                        return hits;                    
                }
                else if(pn.isParsableType() && pn.authorsParsed && pn.getType() != NameType.informal && pn.getType() != NameType.doubtful)
                {
                    //check the canonical name
                    String canonicalName = pn.canonicalName();
                    hits = performSearch(CBCreateLuceneIndex.IndexField.NAME.toString(), canonicalName, rank, cl, max, MatchType.CANONICAL, true, queryParser.get());
                    if(hits.size()>0)
                        return hits;
                    //if the parse type was a cultivar and we didn't match it check to see if we can match as a phrase name
                    if(pn.getType() == NameType.cultivar){
                        String genus = pn.getGenusOrAbove();
                        String phrase = pn.getCultivar();
                        String voucher =null;
                        String specific = pn.rank != null &&pn.rank.equals("sp.")?null:pn.getSpecificEpithet();
                        String[][] searchFields = new String[4][];
                        searchFields[0] = new String[]{RankType.GENUS.getRank(),genus};
                        searchFields[1]= new String[]{NameIndexField.PHRASE.toString(), phrase};
                        searchFields[2] = new String[]{NameIndexField.VOUCHER.toString(), voucher};
                        searchFields[3] = new String[]{NameIndexField.SPECIFIC.toString(), specific};
                        hits = performSearch(searchFields, rank, cl, max, MatchType.PHRASE, false, queryParser.get());
                        if(hits.size()>0)
                            return hits;
                    }
                }
                //now check for a "sounds like" match if we don't have an informal name
                if(fuzzy && pn.isBinomial() && pn.getType() != NameType.informal && pn.getType() != NameType.doubtful){
                    String genus = TaxonNameSoundEx.treatWord(pn.genusOrAbove,"genus");
                    String specific = TaxonNameSoundEx.treatWord(pn.specificEpithet, "species");
                    String infra = pn.infraSpecificEpithet == null?null:TaxonNameSoundEx.treatWord(pn.infraSpecificEpithet, "species");
                    String[][] searchFields = new String[3][];
                    searchFields[0] = new String[]{NameIndexField.GENUS_EX.toString(), genus};
                    searchFields[1] = new String[]{NameIndexField.SPECIES_EX.toString(), specific};
                    if(StringUtils.isNotEmpty(infra)){
                        searchFields[2] = new String[]{NameIndexField.INFRA_EX.toString(), infra};
                    }
                    else{
                        searchFields[2] = new String[] {NameIndexField.INFRA_EX.toString(), "<null>"};
                    }
                    hits = performSearch(searchFields, rank, cl, max, MatchType.SOUNDEX, false, queryParser.get()); //don't want to check for homonyms yet...
                    if(hits.size()>0)
                        return hits;
//                    else if(hits.size()>1){
//                        //We can only make a fuzzy match if the number of results == 1
//                        //Thus we can be more sure that the fuzzy match is correct
//
//                        throw new SearchResultException("Fuzzy match is ambiguous", hits);
//                    }
                }

            }
            catch(UnparsableException e){
                log.warn("Unable to parse " + name + ". " + e.getMessage());
            }

            

            return null;
        }
        catch(IOException e){
            log.warn(e.getMessage());
            return null;
        }


    }
    /**
     * Checks to see if the "soundex" matched results are ambiguous.
     * @param hits
     * @return
     */
    private boolean areMatchesAmbiguous(List<NameSearchResult> hits){
        if(hits.size()>1){
            //not ambiguous if all records have the smae accepted_lsid

        }
        return false;
    }
    /**
     * 
     * @param name
     * @param rank
     * @param cl
     * @param max
     * @param fuzzy
     * @param clean whether or not to clean the name and retest
     * @return
     * @throws SearchResultException
     */
    @Deprecated
    private List<NameSearchResult> searchForRecordsOLD(String name, RankType rank, LinnaeanRankClassification cl, int max, boolean fuzzy, boolean clean) throws SearchResultException{
        //The name is not allowed to be null
        if(name == null)
            throw new SearchResultException("Unable to perform search. Null value supplied for the name.");
        //According to http://en.wikipedia.org/wiki/Species spp. is used as follows:
        //The authors use "spp." as a short way of saying that something applies to many species within a genus,
        //but do not wish to say that it applies to all species within that genus.
        //Thus we don't want to attempt to match on spp.
        if(name.contains("spp."))
            throw new SPPException();//SearchResultException("Unable to perform search. Can not match to a subset of species within a genus.");
        try{

            //1. Direct Name hit
            List<NameSearchResult> hits = performSearch(CBCreateLuceneIndex.IndexField.NAME.toString(), name, rank, cl, max, MatchType.DIRECT, true, queryParser.get());
			if (hits == null) // situation where searcher has not been initialised
				return null;
			if (hits.size() > 0)
				return hits;

			//2. Hit on the alternative names
            //check to see if the name needs a different rank associated with it
            rank = getUpdatedRank(name, rank);


            hits = performSearch(CBCreateLuceneIndex.IndexField.NAMES.toString(), name, rank, cl, max, MatchType.ALTERNATE, true, queryParser.get());
            if(hits.size()>0)
                return hits;


            //3. searchable canonical name if fuzzy match is enabled
            if(fuzzy){
                String searchable = tnse.soundEx(name);
                //searchable canonical should not check for homonyms due to the more erratic nature of the result
                hits = performSearch(CBCreateLuceneIndex.IndexField.SEARCHABLE_NAME.toString(), searchable, rank, cl, max, MatchType.SEARCHABLE, false, idParser.get());
                if(hits.size()>0)
                    return hits;
            }
            //4. if clean = true then clean the name and then search for the new version
            //DON'T search for the clean name if the original name contains a " cf " or " aff " (these are informal names)
            if (clean) {
                try{
                ParsedName<?> cn = parser.parse(name);
                if (cn != null && cn.getType() != NameType.informal) {
                    String cleanName = cn.canonicalName();
                    if (StringUtils.trimToNull(cleanName) != null && !name.equals(cleanName)) {
                        //we only want to clean the name once.  This should prevent incorrect genus match for Astroloma sp. Cataby (EA Griffin 1022)
                        List<NameSearchResult> results = searchForRecords(
                                cleanName, rank, cl, max, fuzzy, false);
                        if (results != null) {
                            for (NameSearchResult result : results) {
                                result.setCleanName(cleanName);
                            }
                        }
                        return results;
                    }
                }
                }
                catch(org.gbif.ecat.parser.UnparsableException e){

                }
            }
        }
        catch(IOException e){
            log.warn(e.getMessage());
            return null;
        }
        return null;
    }

    /**
     * Update the rank for the name based on it containing rank strings.
     * Provides a bit of a sanity check on the name matching.  If we expect a
     * species we don't want to match on a genus
     *
     * @param name
     * @param rank
     *
     */
    private RankType getUpdatedRank(String name, RankType rank){
        Matcher matcher = RANK_MARKER.matcher(name);

        if(matcher.find()){
            String value = name.substring(matcher.start(), matcher.end()) ;
            log.debug("Changing rank to : " + value);
            if(value.endsWith("."))
                rank = RankType.getForCBRank(Rank.RANK_MARKER_MAP.get(value.substring(1, value.length()-1)));
           log.debug("Using the new rank " + rank);
        }
        return rank;
    }

    /**
     * Checks to see if the supplied name is a synonym. A synonym will not have
     * an associated kingdom and genus in the index.
     *
     *
     * @param name
     * @param rank
     * @param kingdom
     * @param genus
     * @param max
     * @throws SearchResultException
     */
    private void checkForSynonym(String name, RankType rank, String kingdom, String genus, int max) throws SearchResultException{
       //search on name field with name and empty kingdom and genus
       //search on the alternative names field with name and empty kingdom and genus
       //if we get a match that is a synonym verify match against IRMNG
    }

    private boolean doesSynonymMatch(String name, RankType rank, String kingdom, String genus){
        return false;
    }

    private List<NameSearchResult> performSearch(String field, String value, RankType rank, LinnaeanRankClassification cl, int max, MatchType type, boolean checkHomo, QueryParser parser)throws IOException, SearchResultException{
        String[][] compValues= new String[1][];
        compValues[0] = new String[]{field, value};
        return performSearch(compValues, rank, cl, max, type, checkHomo, parser);
    }

    /**
     *
     * Performs an index search based on the supplied field and name
     *
     * @param field Index field on which to perform the search
     * @param value The value of which to search
     * @param rank Optional rank of the value
     * @param kingdom Optional kingdom for value
     * @param genus Optional genus for value
     * @param max The maximum number of results to return
     * @param type The type of search that is being performed
     * @param checkHomo Whether or not the result should check for homonyms.
     * @return
     * @throws IOException
     * @throws SearchResultException
     */

    private List<NameSearchResult> performSearch(String[][] compulsoryValues, RankType rank, LinnaeanRankClassification cl, int max, MatchType type, boolean checkHomo, QueryParser parser)throws IOException, SearchResultException{
        if(cbSearcher != null){
            String scientificName = null;
            StringBuilder query = new StringBuilder();
           for(String[] values: compulsoryValues){
               if(values[1] != null){
                
                query.append("+"+values[0] + ":\"" + values[1]+"\"");
                
                if(values[0].equals(NameIndexField.NAME.toString()))
                    scientificName = values[1];
               }

            }
            //Term term = new Term(field, value);
            //Query query = new TermQuery(term);



            //BooleanQuery boolQuery = new BooleanQuery();

            //boolQuery.add(query, Occur.MUST);
            if(rank!=null){
                //if the rank is below species include all names that are species level and below in case synonyms have changed ranks.
                query.append("+(");
                if(rank.getId()>= RankType.SPECIES.getId()){
                    query.append(NameIndexField.RANK_ID.toString()).append(":[7000 TO 9999]");
                    //query.append(CBCreateLuceneIndex.IndexField.RANK.toString()).append(":").append(RankType.INFRASPECIFICNAME.getRank()).append(" OR ");
                }
                else
                    query.append(NameIndexField.RANK.toString() + ":" + rank.getRank());
                //cater for the situation where the search term could be a synonym that does not have a rank
                // also ALA added concepts do NOT have ranks.
                query.append(" OR ").append(NameIndexField.iS_SYNONYM.toString()).append(":T OR ").append(NameIndexField.ALA).append(":T)");
               
            }
            if(cl!= null){
                query.append(cl.getLuceneSearchString(true));

            }

            try{
                //System.out.println(query);
                TopDocs hits = cbSearcher.search(parser.parse(query.toString()), max);//cbSearcher.search(boolQuery, max);

                //now put the hits into the arrayof NameSearchResult
                List<NameSearchResult> results = new java.util.ArrayList<NameSearchResult>();

                for(ScoreDoc sdoc : hits.scoreDocs){
                    results.add(new NameSearchResult(cbReader.document(sdoc.doc), type));
                }

                //HOMONYM CHECKS
                if(checkHomo){
                    //check result level homonyms
                    //TODO 2012-04-17: Work out edge case issues for canonical matches...
                    //checkResultLevelHomonym(results);

                    //check to see if we have a cross rank homonym
                    //cross rank homonyms are resolvable if a rank has been supplied
                    if(rank == null){
                        checkForCrossRankHomonym(results);
                    }

                    //check to see if the search criteria could represent an unresolved genus or species homonym
                    if(results.size() > 0){
                        RankType resRank = results.get(0).getRank();
                        if(resRank == RankType.GENUS || resRank == RankType.SPECIES){
                            NameSearchResult result= validateHomonyms(results,scientificName, cl);
                            results.clear();
                            results.add(result);
                        }
                    }
                }

                return results;
            }
            catch(ParseException e){
                throw new SearchResultException("Error parsing " + query.toString() + "." + e.getMessage());
            }

        }
        return null;
    }

    private void checkResultLevelHomonym(List<NameSearchResult> results)throws HomonymException{
        //They are result level homonyms if multiple records and they don't all point to the same accepted concept...
        //They are not homonyms if they have different Kingdoms...
        if(results.size()>1){
            String lastAcceptedLsid ="";
            String lastKingdom ="";
            boolean lastWasSyn = false;
            for(NameSearchResult result : results){
                if(result.isSynonym() ||result.getRank().getId()>=7000){
                    String accepted = result.isSynonym()? result.getAcceptedLsid() : result.getLsid();
                    String kingdom = result.getRankClassification().getKingdom() == null ? "" : result.getRankClassification().getKingdom();
                    if(lastAcceptedLsid.length()>0){
                        if(!lastAcceptedLsid.equals(accepted) && (lastKingdom.equals(kingdom) || lastWasSyn || result.isSynonym())){
                            throw new HomonymException(accepted, results);
                        }
                    }
                    lastAcceptedLsid = accepted;
                    lastWasSyn = result.isSynonym();
                }
            }
        }

    }
    /**
     * Uses the distance between 2 strings to determine whether or not the
     * 2 strings are a close match.
     *
     * @param s1
     * @param s2
     * @param maxLengthDif  The maximum differences in length that the 2 strings can  be
     * @param maxDist The maximum distance between the 2 strings
     * @return
     */
    private boolean isCloseMatch(String s1, String s2, int maxLengthDif, int maxDist){
        if (s1 != null && s2!=null &&Math.abs(s1.length() - s2.length()) <= maxLengthDif) {
            //if the difference in the length of the 2 strings is at the most maxLengthDif characters compare the L distance
            //log.debug("Difference ("+s1 + ", " + s2+") : " + StringUtils.getLevenshteinDistance(s1, s2));
            return StringUtils.getLevenshteinDistance(s1, s2)<=maxDist;

        }
        return false;
    }
    /**
     * Checks to see if the first result represents a scientific name that is a cross
     * rank homonym.
     *
     * This method should only be called if a rank has not been supplied
     *
     * @param results
     * @throws HomonymException  When the first result's scientific name is a cross rank homonym
     */
    private void checkForCrossRankHomonym(List<NameSearchResult> results) throws HomonymException{
        if(results!= null && results.size()>0){
            if(crossRankHomonyms.contains(results.get(0).getRankClassification().getScientificName().toLowerCase()))
                throw new HomonymException("Cross rank homonym detected.  Please repeat search with a rank specified.", results);
        }
    }



    /**
     * Takes a result set that contains a homonym and then either throws a HomonymException
     * or returns the first result that matches the supplied taxa.
     *
     * AS OF 22/07/2010:
     * Homonyms are ONLY being tested if the result was a genus. According to Tony it is
     * very rare for a species to be a homonym with another species that belongs to a homonym
     * of the same genus.  Eventually we should get a list of the known cases to
     * test against.
     *
     * This should provide overall better name matching.
     *
     * 2011-01-14:
     * The homonym validation has been modified to include species level homonyms.
     * The indexing of the irmng species is different to the genus. IRMNG has a
     * more complete genus coverage than species. Thus only the species that are
     * homonyms are included in the index.
     *
     * @param results
     * @param k
     * @return
     * @throws HomonymException
     */
    public NameSearchResult validateHomonyms(List<NameSearchResult> results, String name, LinnaeanRankClassification cl) throws HomonymException{
        //get the rank so that we know which type of homonym we are evaluating
        RankType rank = results.get(0).getRank();

        //check to see if the homonym is resolvable given the details provide
        try{
            if(cl == null){
                if(rank == RankType.GENUS)
                    cl = new LinnaeanRankClassification(null, name);
                else{
                    cl = new LinnaeanRankClassification(null, null);
                    cl.setSpecies(name);
                }
            }
            if(rank == RankType.GENUS && cl.getGenus() == null)
                cl.setGenus(name);
            else if(rank == RankType.SPECIES && cl.getSpecies() == null)
                cl.setSpecies(name);

            //Find out which rank the homonym can be resolved at.
            //This will indeicate which ranks of the supplied classifications need to match the result's classification in order to resolve the homonym
            RankType resolveLevel = resolveIRMNGHomonym(cl, rank);
            if(resolveLevel == null){
                //there was no need to resolve the homonym
                return results.get(0);
            }
            //result must match at the kingdom level and resolveLevel of the taxonomy (TODO)
            log.debug("resolve the homonym at " + resolveLevel +" rank");



            //the first result should be the one that most closely resembles the required classification

            for(NameSearchResult result : results){
                if(result.isSynonym()){
                    //if the result is a synonym it is difficult to resolve the homonym.
                    //This is because synonyms do not have the corresponding classificaitons.
                    //There are 2 situations that we *may* be able to resolve the homonym
                    // 1) The IRMNG entry that resolves the homonym includes an "accepted" concepts
                    // 2) The resolveLevel is Kingdom and we make an assumption that the concept has not changed kingdoms
                    //    -- This is not always true especially with plants/algae/fungi and animalia/protozoa
                    //TODO algorithm to handle this situations see above comment

                }
                else{
                    if(cl.hasIdenticalClassification(result.getRankClassification(), resolveLevel))
                        return result;
                }
            }

            throw new HomonymException(results);
        }
        catch(HomonymException e){
            e.setResults(results);
            throw e;
        }

    }
    /**
     * Uses the IRMNG index to determine whether or not a homonym can be resolved
     * with the supplied details.
     * @return
     */
    private boolean isHomonymResolvable(LinnaeanRankClassification cl){
        TopDocs results = getIRMNGGenus(cl, RankType.GENUS);
        if(results != null)
            return results.totalHits <=1;
        return false;
    }

    /**
     * Multiple genus indicate that an unresolved homonym exists for the supplied
     * search details.
     * @param cl The classification to test
     * @param rank The rank level of the homonym being tested either RankType.GENUS or RankType.SPECIES
     */
    public TopDocs getIRMNGGenus(LinnaeanRankClassification cl, RankType rank){
        if(cl != null && (cl.getGenus() != null || cl.getSpecies() != null)){

            try{

                String searchString = "+rank:"+rank+" "+ cl.getLuceneSearchString(false).trim();


                log.debug("Search string : " + searchString + " classification : " + cl);
                Query query = queryParser.get().parse(searchString);
                log.debug("getIRMNG query: " +query.toString());
                return irmngSearcher.search(query, 10);

            }
            catch(Exception e){
                log.warn("Error searching IRMNG index." , e);
            }
        }
        return null;
    }

    /**
     * Attempt to resolve the homonym using the IRMNG index.
     *
     * The ability to resolve the homonym is dependent on the quality and quantity
     * of the higher taxa provided in the search via cl.
     * @param cl  The classification used to determine the rank at which the homonym is resolvable
     * @return
     * @throws HomonymException
     */
    public RankType resolveIRMNGHomonym(LinnaeanRankClassification cl, RankType rank) throws HomonymException{
        //check to see if we need to resolve the homonym
        if(cl.getGenus() != null || cl.getSpecies() != null){
            LinnaeanRankClassification newcl = new LinnaeanRankClassification(null, cl.getGenus());
            if(rank == RankType.SPECIES)
                newcl.setSpecies(cl.getSpecies());
            if(cl != null && (cl.getGenus() != null || cl.getSpecies() != null)){
                TopDocs results = getIRMNGGenus(newcl, rank);
                if(results == null || results.totalHits <= 1)
                    return null;

            if(cl != null && cl.getKingdom() != null){
                //create a local classification to work with we will only add a taxon when we are ready to try and resolve with it
                newcl.setKingdom(cl.getKingdom());
                //Step 1 search for kingdom and genus
                results = getIRMNGGenus(newcl, rank);
                if(results.totalHits == 1)
                    return RankType.KINGDOM;
            }
                //Step 2 add the phylum
                if(cl.getPhylum() != null && results.totalHits>1){
                        newcl.setPhylum(cl.getPhylum());
                        results = getIRMNGGenus(newcl, rank);
                        if(results.totalHits == 1)
                            return RankType.PHYLUM;
                        //This may not be a good idea
                        else if(results.totalHits ==0)
                            newcl.setPhylum(null);//just in case the phylum was specified incorrectly
                }
                //Step 3 try the class
                if(cl.getKlass() != null){// && results.totalHits>1){
                    newcl.setKlass(cl.getKlass());
                    results = getIRMNGGenus(newcl, rank);
                    if(results.totalHits == 1)
                        return RankType.CLASS;

                }
                //step 4 try order
                if(cl.getOrder() != null && results.totalHits>1){
                    newcl.setOrder(cl.getOrder());
                    results = getIRMNGGenus(newcl, rank);
                    if(results.totalHits == 1)
                        return RankType.ORDER;
                }
                //step 5 try  the family
                if(cl.getFamily() != null && results.totalHits>1){
                    newcl.setFamily(cl.getFamily());
                    results = getIRMNGGenus(newcl, rank);
                    if(results.totalHits == 1)
                        return RankType.FAMILY;
                }
            }
        }
        throw new HomonymException("Problem resolving the classification: "+cl);
    }

    private String getValueForSynonym(String name){
        //get the genus for the name
        try{
        ParsedName<?> pn =  parser.parse(name);
        if(pn!= null){
            String genus = pn.getGenusOrAbove();
            LinnaeanRankClassification cl = new LinnaeanRankClassification(null, genus);
            TopDocs docs = getIRMNGGenus(cl, RankType.GENUS);
            try{
            if(docs.totalHits>0)
                return irmngSearcher.doc(docs.scoreDocs[0].doc).get(RankType.KINGDOM.getRank());
            }
            catch(IOException e){
                log.warn("Unable to get value for synonym. " ,e);
            }
            //seach for the genus in irmng
            //return simpleIndexLookup(irmngSearcher, RankType.GENUS.getRank(), genus, RankType.KINGDOM.getRank());
        }
        }
        catch(org.gbif.ecat.parser.UnparsableException e){

        }
        return null;
    }

    /**
     * Performs a search on the common name index for the supplied name.
     * @param commonName
     * @return
     */
    public String searchForLSIDCommonName(String commonName){

        return getLSIDForUniqueCommonName(commonName);
    }
    /**
     * Returns the LSID for the CB name usage for the supplied common name.
     *
     * When the common name returns more than 1 hit a result is only returned if all the scientific names match
     * @param name
     * @return
     */
    private String getLSIDForUniqueCommonName(String name){
        if(name != null){
            TermQuery query = new TermQuery(new Term(CBCreateLuceneIndex.IndexField.COMMON_NAME.toString(), name.toUpperCase().replaceAll("[^A-Z0-9ÏËÖÜÄÉÈČÁÀÆŒ]", "")));
            try{
                TopDocs results = vernSearcher.search(query, 10);
                //if all the results have the same scientific name result the LSID for the first
                String firstLsid = null;
                String firstName = null;
                log.debug("Number of matches for " + name + " " + results.totalHits);
                for(ScoreDoc sdoc: results.scoreDocs){
                    org.apache.lucene.document.Document doc =vernSearcher.doc(sdoc.doc);
                    if(firstLsid == null){
                        firstLsid = doc.get(CBCreateLuceneIndex.IndexField.LSID.toString());
                        firstName = doc.get(CBCreateLuceneIndex.IndexField.NAME.toString());
                    }
                    else{
                        if(!doSciNamesMatch(firstName, doc.get(CBCreateLuceneIndex.IndexField.NAME.toString())))
                            return null;
                    }
                }
                return firstLsid;
            }
            catch(IOException e){
                //
                log.debug("Unable to access document for common name." , e);
            }
        }
        return null;
    }
    /**
     * Returns true when the parsed names match.
     * @param n1
     * @param n2
     * @return
     */
    private boolean doSciNamesMatch(String n1, String n2){
        try{
        ParsedName<?> pn1 =  parser.parse(n1);
        ParsedName<?> pn2 =  parser.parse(n2);
        if(pn1 != null && pn2!= null)
            return pn1.canonicalName().equals(pn2.canonicalName());
        return false;
        }
        catch(org.gbif.ecat.parser.UnparsableException e){
            return false;
        }
    }
    /**
     * Performs a search on the supplied common name returning a NameSearchResult.
     * Useful if you required CB ID's etc.
     * @param name
     * @return
     */
    public NameSearchResult searchForCommonName(String name){
        NameSearchResult result = null;
        String lsid = getLSIDForUniqueCommonName(name);
        if(lsid != null){
            //we need to get the CB ID for the supplied LSID
            result =searchForRecordByLsid(lsid);
            if(result != null)
                result.setMatchType(MatchType.VERNACULAR);
        }
        return result;
    }
    /**
     * Returns the primary LSID for the supplied lsid.
     *
     * This is useful in the situation where multiple LSIDs are associated with
     * a scientific name and there is a reference to the non-primary LSID.
     *
     * @param lsid
     * @return
     */
    public String getPrimaryLsid(String lsid){

        TermQuery tq = new TermQuery(new Term("lsid", lsid));
        try{
        org.apache.lucene.search.TopDocs results = idSearcher.search(tq, 1);
        if(results.totalHits>0)
            return idSearcher.doc(results.scoreDocs[0].doc).get("reallsid");
        }catch(IOException e){}

        return lsid;
    }
    public NameSearchResult searchForRecordByLsid(String lsid){
        NameSearchResult result = null;
        try{
            List<NameSearchResult> results= performSearch(CBCreateLuceneIndex.IndexField.LSID.toString(), lsid, null, null, 1,MatchType.DIRECT, false, idParser.get());
                if(results.size()>0)
                    result = results.get(0);
        }
        catch(Exception e){
            //we are not checking for homonyms so this should never happen
            log.error("Unable to search for record by LSID");
        }
        return result;
    }
}
