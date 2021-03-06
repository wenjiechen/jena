/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.fuseki.server;

import java.io.* ;
import java.nio.file.Files ;
import java.nio.file.Path ;
import java.nio.file.Paths ;
import java.nio.file.StandardCopyOption ;
import java.util.ArrayList ;
import java.util.HashMap ;
import java.util.List ;
import java.util.Map ;

import org.apache.jena.atlas.io.IO ;
import org.apache.jena.atlas.lib.DS ;
import org.apache.jena.atlas.lib.FileOps ;
import org.apache.jena.atlas.lib.InternalErrorException ;
import org.apache.jena.atlas.lib.Lib ;
import org.apache.jena.fuseki.Fuseki ;
import org.apache.jena.fuseki.FusekiConfigException ;
import org.apache.jena.fuseki.FusekiLib ;
import org.apache.jena.fuseki.build.Builder ;
import org.apache.jena.fuseki.build.FusekiConfig ;
import org.apache.jena.fuseki.build.Template ;
import org.apache.jena.fuseki.build.TemplateFunctions ;
import org.apache.jena.fuseki.servlets.ServletOps ;
import org.apache.jena.riot.Lang ;
import org.apache.jena.riot.RDFDataMgr ;
import org.apache.jena.riot.RDFLanguages ;
import arq.cmd.CmdException ;

import com.hp.hpl.jena.rdf.model.* ;
import com.hp.hpl.jena.sparql.core.DatasetGraph ;
import com.hp.hpl.jena.tdb.sys.Names ;
import com.hp.hpl.jena.tdb.sys.SystemTDB ;

public class FusekiServer
{
    /** Root of the Fuseki installation for fixed files. 
     * This may be null (e.g. running inside a web application container) */ 
    public static Path FUSEKI_HOME = null ;
    
    /** Root of the varying files in this deployment. Often $FUSEKI_HOME/run.
     * This is not null - it may be /etc/fuseki, which must be writable.
     */ 
    public static Path FUSEKI_BASE = null ;
    
    public static final boolean isWindows = SystemTDB.isWindows ;
 
    /** Unused */
    //public static final String DFT_FUSEKI_HOME  = 
    //    isWindows ? /*What's correct here?*/ "/usr/share/fuseki" : "/usr/share/fuseki" ;
    public static final String DFT_FUSEKI_BASE  = 
        isWindows ? /*What's correct here?*/ "/etc/fuseki"       : "/etc/fuseki" ;
    
    // In FUSEKI_BASE
    public static final String DFT_CONFIG       = "config.ttl" ;

    // Relative names of directories
    private static final String        runArea                  = "run" ;
    private static final String        databasesLocationBase    = "databases" ;
    //private static final String        databaseIndexesDir       = "indexes" ;       // Place to put Lucene text and spatial indexes.  
    private static final String        backupDirNameBase        = "backups" ;
    private static final String        configDirNameBase        = "configuration" ;
    private static final String        logsNameBase             = "logs" ;
    private static final String        systemDatabaseNameBase   = "system" ;
    private static final String        systemFileAreaBase       = "system_files" ;
    private static final String        templatesNameBase        = "templates" ;
    private static final String        DFT_SHIRO_INI            = "shiro.ini" ; // This name is in web.xml as well. 
    
    // --- Set during server initialization

    /** Directory for TDB databases - this is known to the assembler templates */
    public static Path        dirDatabases       = null ;
    
    /** Directory for writing backups */
    public static Path        dirBackups         = null ;

    /** Directory for assembler files */
    public static Path        dirConfiguration   = null ;
    
    /** Directory for assembler files */
    public static Path        dirLogs            = null ;

    /** Directory for system database */
    public static Path        dirSystemDatabase  = null ;

    /** Directory for files uploaded (e.g upload assmbler descriptions); not data uploads. */
    public static Path        dirFileArea        = null ;
    
    /** Directory for assembler files */
    public static Path        dirTemplates       = null ;

    private static boolean    initialized        = false ;
    public static boolean     serverInitialized  = false ;

    /** For testing - reset the places which initialize once */
    public synchronized static void reset() {
        initialized = false ;
        FusekiServer.initialized = false ;
    }
    
    public synchronized static void init() {
        if ( initialized )
            return ;
        initialized = true ;
        try {
            Fuseki.init() ;

            // ----  Set and check FUSEKI_HOME and FUSEKI_BASE

            if ( FUSEKI_HOME == null ) {
                // Make absolute
                String x1 = FusekiLib.getenv("FUSEKI_HOME") ;
                if ( x1 != null )
                    FUSEKI_HOME = Paths.get(x1) ;
            }

            if ( FUSEKI_BASE == null ) {
                String x2 = FusekiLib.getenv("FUSEKI_BASE") ;
                if ( x2 != null )
                    FUSEKI_BASE = Paths.get(x2) ;
                else {
                    if ( FUSEKI_HOME != null )
                        FUSEKI_BASE = FUSEKI_HOME.resolve(runArea) ;
                    else
                        // Neither FUSEKI_HOME nor FUSEKI_BASE set.
                        FUSEKI_BASE = Paths.get(DFT_FUSEKI_BASE) ;
                }
            }

            if ( FUSEKI_HOME != null )
                FUSEKI_HOME = FUSEKI_HOME.toAbsolutePath() ;

            FUSEKI_BASE = FUSEKI_BASE.toAbsolutePath() ;

            Fuseki.configLog.info("FUSEKI_HOME="+ ((FUSEKI_HOME==null) ? "unset" : FUSEKI_HOME.toString())) ;
            Fuseki.configLog.info("FUSEKI_BASE="+FUSEKI_BASE.toString());

            // If FUSEKI_HOME exists, it may be FUSEKI_BASE.

            if ( FUSEKI_HOME != null ) {
                if ( ! Files.isDirectory(FUSEKI_HOME) )
                    throw new FusekiConfigException("FUSEKI_HOME is not a directory: "+FUSEKI_HOME) ;
                if ( ! Files.isReadable(FUSEKI_HOME) )
                    throw new FusekiConfigException("FUSEKI_HOME is not readable: "+FUSEKI_HOME) ;
            }

            if ( Files.exists(FUSEKI_BASE) ) {
                if ( ! Files.isDirectory(FUSEKI_BASE) )
                    throw new FusekiConfigException("FUSEKI_BASE is not a directory: "+FUSEKI_BASE) ;
                if ( ! Files.isWritable(FUSEKI_BASE) )
                    throw new FusekiConfigException("FUSEKI_BASE is not writable: "+FUSEKI_BASE) ;
            } else {
                ensureDir(FUSEKI_BASE);
            }

            // Ensure FUSEKI_BASE has the assumed directories.
            dirTemplates        = writeableDirectory(FUSEKI_BASE, templatesNameBase) ;
            dirDatabases        = writeableDirectory(FUSEKI_BASE, databasesLocationBase) ;
            dirBackups          = writeableDirectory(FUSEKI_BASE, backupDirNameBase) ;
            dirConfiguration    = writeableDirectory(FUSEKI_BASE, configDirNameBase) ;
            dirLogs             = writeableDirectory(FUSEKI_BASE, logsNameBase) ;
            dirSystemDatabase   = writeableDirectory(FUSEKI_BASE, systemDatabaseNameBase) ;
            dirFileArea         = writeableDirectory(FUSEKI_BASE, systemFileAreaBase) ;
            //Possible intercept point

            // ---- Initialize with files.

            if ( Files.isRegularFile(FUSEKI_BASE) ) 
                throw new FusekiConfigException("FUSEKI_BASE exists but is a file") ;

            // Copy missing files into FUSEKI_BASE
            copyFileIfMissing(null, DFT_SHIRO_INI, FUSEKI_BASE) ;
            copyFileIfMissing(null, DFT_CONFIG, FUSEKI_BASE) ;
            for ( String n : Template.templateNames ) {
                copyFileIfMissing(null, n, FUSEKI_BASE) ;
            }

            serverInitialized = true ;
        } catch (RuntimeException ex) {
            Fuseki.serverLog.error("Exception in server initialization", ex) ;
            throw ex ;
        }
    }
    
    private static boolean emptyDir(Path dir) {
        return dir.toFile().list().length <= 2 ;
    }
    
    /** Copy a file from src to dst under name fn.
     * If src is null, try as a classpath resource
     * @param src   Source directory, or null meaning use java resource. 
     * @param fn    File name, a relative path.
     * @param dst   Destination directory.
     * 
     */
    private static void copyFileIfMissing(Path src, String fn, Path dst) {
        
        Path dstFile = dst.resolve(fn) ;
        if ( Files.exists(dstFile) )
            return ;
        
        // fn may be a path.
        if ( src != null ) {
            try {
                Files.copy(src.resolve(fn), dstFile, StandardCopyOption.COPY_ATTRIBUTES) ;
            } catch (IOException e) {
                IO.exception("Failed to copy file "+src, e);
                e.printStackTrace();
            }
        } else {
            try {
                // Get from the file from area "org/apache/jena/fuseki/server"  (our package)
                InputStream in = FusekiServer.class.getResource(fn).openStream() ;
                Files.copy(in, dstFile) ;
            }
            catch (IOException e) {
                IO.exception("Failed to copy file from resource: "+src, e);
                e.printStackTrace();
            }
        }
    }

    public static void initializeDataAccessPoints(ServerInitialConfig initialSetup, String configDir) {
        List<DataAccessPoint> configFileDBs = initServerConfiguration(initialSetup) ;
        List<DataAccessPoint> directoryDBs =  FusekiConfig.readConfigurationDirectory(configDir) ;
        List<DataAccessPoint> systemDBs =     FusekiConfig.readSystemDatabase(SystemState.getDataset()) ;
        
        List<DataAccessPoint> datapoints = new ArrayList<DataAccessPoint>() ;
        datapoints.addAll(configFileDBs) ;
        datapoints.addAll(directoryDBs) ;
        datapoints.addAll(systemDBs) ;
        
        // Having found them, set them all running.
        enable(datapoints);
    }

    private static void enable(List<DataAccessPoint> datapoints) {
        for ( DataAccessPoint dap : datapoints ) {
            Fuseki.configLog.info("Register: "+dap.getName()) ;
            DataAccessPointRegistry.register(dap.getName(), dap); 
        }
    }

    private static List<DataAccessPoint> initServerConfiguration(ServerInitialConfig params) { 
        // Has a side effect of global context setting
        // when processing a config file.
        // Compatibility.
        
        List<DataAccessPoint> datasets = DS.list() ;
        if ( params == null )
            return datasets ;

        if ( params.fusekiConfigFile != null ) {
            if ( FileOps.exists(params.fusekiConfigFile ) ) {
                Fuseki.configLog.info("Configuration file: " + params.fusekiConfigFile) ;
                List<DataAccessPoint> cmdLineDatasets = FusekiConfig.readConfigFile(params.fusekiConfigFile) ;
                datasets.addAll(cmdLineDatasets) ;
            } else {
                Fuseki.configLog.info("Configuration file '" + params.fusekiConfigFile+"' does not exist") ;
            }
        } else if ( params.dsg != null ) {
            DataAccessPoint dap = defaultConfiguration(params.datasetPath, params.dsg, params.allowUpdate) ;
            datasets.add(dap) ;
        } else if ( params.templateFile != null ) {
            Fuseki.configLog.info("Template file: " + params.templateFile) ;
            String dir = params.params.get(Template.DIR) ;
            if ( dir != null ) {
                if ( Lib.equal(dir, Names.memName) ) {
                    Fuseki.configLog.info("TDB dataset: in-memory") ;
                } else {
                    if ( !FileOps.exists(dir) )
                        throw new CmdException("Directory not found: " + dir) ;
                    Fuseki.configLog.info("TDB dataset: directory=" + dir) ;
                }
            }
            DataAccessPoint dap = configFromTemplate(params.templateFile, params.datasetPath, params.params) ;
            datasets.add(dap) ;
        }
        // No datasets is valid.
        return datasets ;
    }
    
    private static DataAccessPoint configFromTemplate(String templateFile, 
                                                      String datasetPath, 
                                                      Map<String, String> params) {
        datasetPath = DataAccessPoint.canonical(datasetPath) ;
        
        // DRY -- ActionDatasets (and others?)
        if ( params == null ) {
            params = new HashMap<>() ;
            params.put(Template.NAME, datasetPath) ;
        } else {
            if ( ! params.containsKey(Template.NAME) ) {
                Fuseki.configLog.warn("No NAME found in template parameters (added)") ;
                params.put(Template.NAME, datasetPath) ;   
            }
        }
        
        addGlobals(params); 

        String str = TemplateFunctions.templateFile(templateFile, params) ;
        Lang lang = RDFLanguages.filenameToLang(str, Lang.TTL) ;
        StringReader sr =  new StringReader(str) ;
        Model model = ModelFactory.createDefaultModel() ;
        RDFDataMgr.read(model, sr, datasetPath, lang);
        
        // Find DataAccessPoint
        Statement stmt = getOne(model, null, FusekiVocab.pServiceName, null) ;
        if ( stmt == null ) {
            StmtIterator sIter = model.listStatements(null, FusekiVocab.pServiceName, (RDFNode)null ) ;
            if ( ! sIter.hasNext() )
                ServletOps.errorBadRequest("No name given in description of Fuseki service") ;
            sIter.next() ;
            if ( sIter.hasNext() )
                ServletOps.errorBadRequest("Multiple names given in description of Fuseki service") ;
            throw new InternalErrorException("Inconsistent: getOne didn't fail the second time") ;
        }
        Resource subject = stmt.getSubject() ;
        DataAccessPoint dap = Builder.buildDataAccessPoint(subject) ;
        return dap ;
    }
    
    public static void addGlobals(Map<String, String> params) {
        if ( params == null ) {
            Fuseki.configLog.warn("FusekiServer.addGlobals : params is null", new Throwable()) ;
            return ;
        }
        
        if ( ! params.containsKey("FUSEKI_BASE") )
            params.put("FUSEKI_BASE", pathStringOrElse(FUSEKI_BASE, "unset")) ;
        if ( ! params.containsKey("FUSEKI_HOME") )
            params.put("FUSEKI_HOME", pathStringOrElse(FUSEKI_HOME, "unset")) ;
    }

    private static String pathStringOrElse(Path path, String dft) {
        if ( path == null )
            return dft ;
        return path.toString() ;
    }
    
    // DRY -- ActionDatasets (and others?)
    private static Statement getOne(Model m, Resource s, Property p, RDFNode o) {
        StmtIterator iter = m.listStatements(s, p, o) ;
        if ( ! iter.hasNext() )
            return null ;
        Statement stmt = iter.next() ;
        if ( iter.hasNext() )
            return null ;
        return stmt ;
    }
    
    private static DataAccessPoint defaultConfiguration( String name, DatasetGraph dsg, boolean updatable) {
        name = DataAccessPoint.canonical(name) ;
        DataAccessPoint dap = new DataAccessPoint(name) ;
        DataService ds = Builder.buildDataService(dsg, updatable) ;
        dap.setDataService(ds) ;
        return dap ;
    }
    
    // ---- Helpers

    /** Ensure a directory exists, creating it if necessary.
     */
    private static void ensureDir(Path directory) {
        File dir = directory.toFile() ;
        if ( ! dir.exists() ) {
            boolean b = dir.mkdirs() ;
            if ( ! b )
                throw new FusekiConfigException("Failed to create directory: "+directory) ;
        }
        else if ( ! dir.isDirectory())
            throw new FusekiConfigException("Not a directory: "+directory) ;
    }

    private static void mustExist(Path directory) {
        File dir = directory.toFile() ;
        if ( ! dir.exists() )
            throw new FusekiConfigException("Does not exist: "+directory) ; 
        if ( ! dir.isDirectory())
            throw new FusekiConfigException("Not a directory: "+directory) ;
    }
    
    private static boolean exists(Path directory) {
        File dir = directory.toFile() ;
        return dir.exists() ;
    }

    private static Path writeableDirectory(Path root , String relName ) {
        Path p = makePath(root, relName) ;
        ensureDir(p);
        if ( ! Files.isWritable(p) )
            throw new FusekiConfigException("Not writable: "+p) ;
        return p ;
    }
    
    private static Path makePath(Path root , String relName ) {
        Path path = root.resolve(relName) ;
        // Must exist
//        try { path = path.toRealPath() ; }
//        catch (IOException e) { IO.exception(e) ; }
        return path ;
    }
}
