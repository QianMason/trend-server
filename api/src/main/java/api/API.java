package api ;

import nojava.* ;

import java.util.* ;
import java.io.* ;

import java.util.concurrent.BlockingQueue ;
import java.util.concurrent.LinkedBlockingQueue ;
import java.util.concurrent.ConcurrentHashMap ;
import java.util.Collection ;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;


import org.json.* ;
import org.restlet.resource.*;
import org.restlet.representation.* ;
import org.restlet.ext.json.* ;
import org.restlet.data.* ;


public class API implements Runnable {

	// queue of new documents
    private static BlockingQueue<Document> CREATE_QUEUE = new LinkedBlockingQueue<Document>() ;						

    // key to record map
    private static ConcurrentHashMap<String,Document> KEYMAP_CACHE = new ConcurrentHashMap<String,Document>() ; 	


    // Background Thread
	@Override
	public void run() {
		while (true) {
			try {
				// sleep for 5 seconds
				try { Thread.sleep( 5000 ) ; } catch ( Exception e ) {}  

				// process any new additions to database
				Document doc = CREATE_QUEUE.take();
  				SM db = SMFactory.getInstance() ;
        		SM.OID record_id  ;
        		String record_key ;
        		SM.Record record  ;
        		String jsonText = doc.json ;
            	int size = jsonText.getBytes().length ;
            	record = new SM.Record( size ) ;
            	record.setBytes( jsonText.getBytes() ) ;
            	record_id = db.store( record ) ;
            	record_key = new String(record_id.toBytes()) ;
            	doc.record = record_key ;
            	doc.json = "" ;
            	KEYMAP_CACHE.put( doc.key, doc ) ;    
                System.out.println( "Created Document: " + doc.key ) ;
                
                // sync nodes
                AdminServer.syncDocument( doc.key, "create" ) ; 

			} catch (InterruptedException ie) {
				ie.printStackTrace() ;
			} catch (Exception e) {
				System.out.println( e ) ;
			}			
		}
	}    


    public static Document[] get_hashmap() {
    	return (Document[]) KEYMAP_CACHE.values().toArray(new Document[0]) ;
    }


    public static void save_hashmap() {
		try {
		  FileOutputStream fos = new FileOutputStream("index.db");
		  ObjectOutputStream oos = new ObjectOutputStream(fos);
		  oos.writeObject(KEYMAP_CACHE);
		  oos.close();
		  fos.close();
		} catch(IOException ioe) {
		  ioe.printStackTrace();
		}
    }


    public static void load_hashmap() {
		 try {
		     FileInputStream fis = new FileInputStream("index.db") ;
		     ObjectInputStream ois = new ObjectInputStream(fis) ;
		     KEYMAP_CACHE = (ConcurrentHashMap) ois.readObject();
		     ois.close() ;
		     fis.close() ;
		  } catch(IOException ioe) {
		     ioe.printStackTrace() ;
		  } catch(ClassNotFoundException c) {
		     System.out.println("Class not found");
		     c.printStackTrace() ;
		  }
    }

    public static String[] copy_clock(String[] vclock) {
	    String[] clock = new String[6];
	    clock[0] = vclock[0];
        clock[1] = vclock[1];
        clock[2] = vclock[2];
        clock[3] = vclock[3];
        clock[4] = vclock[4];
        clock[5] = vclock[5];
        return clock;
    }

    public static int[] parse_version(String local, String sync) {
	    int[] versions = new int[2];
        int my_v;
        int sync_v;
        if (local == null) {
            my_v = 0;
        } else {
            String[] my_split = local.split(":") ;
            my_v = Integer.parseInt(my_split[1]) ;
        }
        if (sync == null) {
            sync_v = 0;
        } else {
            String[] sync_split = sync.split(":") ;
            sync_v = Integer.parseInt(sync_split[1]) ;
        }
        versions[0] = my_v;
        versions[1] = sync_v;
        return versions;
    }

    public static String[] merge_clocks(String[] local, String[] sync) {
	    System.out.println("mergeing clocks") ;
	    System.out.println("Local: " + Arrays.toString(local));
        System.out.println("Sync: " + Arrays.toString(sync));
        for (int i = 1; i < 6; i++) {
            String my_version = local[i] ;
            String sync_version = sync[i] ;
            int[] v = parse_version(my_version, sync_version) ;
            int my_v = v[0] ;
            int sync_v = v[1] ;
            if (my_v < sync_v) {
                local[i] = sync[i];
            }
        }
        System.out.println("final clock") ;
        System.out.println(Arrays.toString(local)) ;
        return local;
    }

    public static boolean should_sync(Document doc, String[] vclock) { //document local, vclock from syncrequest vclock
        System.out.println("---------------------------------1-----------------------------------");
        boolean greater = false;
        boolean lesser = false;
        for (int i = 1; i < 6; i++) {
            String my_version = doc.vclock[i] ;
            String sync_version = vclock[i] ;
            int[] v = parse_version(my_version, sync_version) ;
            int my_v = v[0] ;
            int sync_v = v[1] ;
            if (my_v < sync_v) {
                lesser = true;
            } else if (my_v > sync_v) {
                greater = true;
            }
        }
        System.out.println("---------------------------------2-----------------------------------");
        boolean sync = false;
        if (lesser && greater) { //conflict
            System.out.println("conflict case");
            for (int i = 5; i > 0; i--) {
                String my_version = doc.vclock[i] ; //
                String sync_version = vclock[i] ;
                int[] v = parse_version(my_version, sync_version) ;
                int my_v = v[0] ;
                int sync_v = v[1] ;
                if (sync_v > my_v) {
                    sync = true;
                    break;
                } else if (sync_v < my_v) {
                    break;
                }
            }
        } else if (lesser) { //accept update, merge clocks
            sync = true;
            System.out.println("accept sync case");
        } else if (greater) { //reject sync, merge clocks
            System.out.println("reject sync case") ;
        }
        return sync;
    }

    public static void sync_document(SyncRequest sync) throws DocumentException {

        String key = sync.key ;
        String value = sync.json ;
        String[] vclock = sync.vclock ;
        String command = sync.command ;
        String record_key ;
        SM db = SMFactory.getInstance() ;
        SM.Record found ;
        SM.Record record ;
        SM.OID update_id ;
        SM.OID record_id ;
        String jsonText = value;
        int size = jsonText.getBytes().length ;
        Document doc;
        try {

            AdminServer server = AdminServer.getInstance() ;
            String my_host = server.getMyHostname() ;
            int my_index = server.nodeIndex( my_host ) ;

            switch ( command ) {
                case "create": //recreate create (for case where u already have document)
                    // this code only for case where doc does not exist already
                    if (KEYMAP_CACHE.get(key) == null) {
                        doc = new Document() ;
                        doc.vclock[0] = my_host ;
                        doc.vclock[1] = vclock[1] ;
                        doc.vclock[2] = vclock[2] ;
                        doc.vclock[3] = vclock[3] ;
                        doc.vclock[4] = vclock[4] ;
                        doc.vclock[5] = vclock[5] ;
                        doc.vclock[my_index] = my_host + ":" + Integer.toString(0) ;
                        record = new SM.Record( size ) ;
                        record.setBytes( jsonText.getBytes() ) ;
                        record_id = db.store( record ) ;
                        record_key = new String(record_id.toBytes()) ;
                        doc.record = record_key ;
                        doc.json = "" ;
                        doc.key = key ;
                        KEYMAP_CACHE.put( key, doc ) ;
                        System.out.println( "SYNC: Created Document Key: " + key
                                + " Record: " + record_key
                                + " vClock: " + Arrays.toString(doc.vclock)
                        ) ;
                        break ;
                    } else {
                        doc = KEYMAP_CACHE.get( key ) ;
                        if ( doc == null || doc.record == null ) {
                            throw new DocumentException("Document Not Found: " + key);
                        }
                        boolean sync_bool = should_sync(doc, vclock) ;
                        if (sync_bool) {
                            record_key = doc.record ;
                            record_id = db.getOID( record_key.getBytes() ) ;
                            jsonText = value ;
                            try {
                                // store json to db
                                doc.json = value ;
                                record = new SM.Record( size ) ;
                                record.setBytes( jsonText.getBytes() ) ;
                                update_id = db.update( record_id, record ) ;
                                System.out.println( "Document Updated: " + key ) ;
                            } catch (SM.NotFoundException nfe) {
                                throw new DocumentException( "Document Not Found: " + key ) ;
                            } catch (Exception e) {
                                throw new DocumentException( e.toString() ) ;
                            }
                        }
                        merge_clocks(doc.vclock, vclock);
                        break ;

                    }

                case "update":
                    System.out.println("in update case!!!!!!!!!!!!!!!") ;
                    doc = KEYMAP_CACHE.get( key ) ;
                    if ( doc == null || doc.record == null )
                        throw new DocumentException( "Document Not Found: " + key ) ;
                    boolean should_sync = should_sync(doc, vclock) ;
                    System.out.println("---------------------------------3-----------------------------------");
                    if (should_sync) {
                        record_key = doc.record ;
                        record_id = db.getOID( record_key.getBytes() ) ;
                        jsonText = value ;
                        try {
                            // store json to db
                            record = new SM.Record( size ) ;
                            record.setBytes( jsonText.getBytes() ) ;
                            update_id = db.update( record_id, record ) ;
                            doc.json = value ;
                            System.out.println( "Document Updated: " + key ) ;
                        } catch (SM.NotFoundException nfe) {
                            throw new DocumentException( "Document Not Found: " + key ) ;
                        } catch (Exception e) {
                            throw new DocumentException( e.toString() ) ;
                        }
                    }
                    merge_clocks(doc.vclock, vclock);
                    break ;
                case "delete":
                    System.out.println("in delete case!!!!!!!!!!!!!!!") ;
                    doc = KEYMAP_CACHE.get(key) ;
                    if ( doc == null || doc.record == null ) {
                        throw new DocumentException("Document Not Found: " + key);
                    }
                    boolean sync_bool = should_sync(doc, vclock) ;
                    if (sync_bool) {
                        try {
                            doc.json = null ;
                            System.out.println( "Document Deleted: " + key ) ;
                        } catch (Exception e) {
                            throw new DocumentException( e.toString() ) ;
                        }
                    }
                    merge_clocks(doc.vclock, vclock) ;
                    break ;
            }   

        } catch (Exception e) {
            throw new DocumentException( e.toString() ) ;
        }

    }


    public static void create_document(String key, String value) throws DocumentException {
    	try {
    	    if (KEYMAP_CACHE.get(key) == null) {
                System.out.println( "Create Document: Key = " + key + " Value = " + value ) ;
                Document doc = new Document() ;
                doc.key = key ;
                AdminServer server = AdminServer.getInstance() ;
                String my_host = server.getMyHostname() ;
                System.out.println( "My Host Name: " + my_host ) ;
                doc.vclock[0] = my_host ;
                String my_version = my_host + ":" + Integer.toString(1) ;
                int my_index = server.nodeIndex( my_host ) ;
                System.out.println( "Node Index: " + my_index ) ;
                doc.vclock[my_index] = my_version ;
                KEYMAP_CACHE.put( key, doc ) ;
                doc.json = value ;
                CREATE_QUEUE.put( doc ) ;
                System.out.println( "New Document Queued: " + key ) ;
            } else {
                Document doc = KEYMAP_CACHE.get(key) ;
                if ( doc == null || doc.record == null ) {
                    throw new DocumentException("Document Not Found: " + key);
                }
                doc.json = value ;
                // update vclock
                AdminServer server = AdminServer.getInstance() ;
                String my_host = server.getMyHostname() ;
                doc.vclock[0] = my_host ;
                int my_index = server.nodeIndex( my_host ) ;
                String old_version = doc.vclock[my_index] ;
                String[] splits = old_version.split(":") ;
                int version = Integer.parseInt(splits[1])+1 ;
                String new_version = my_host + ":" + Integer.toString(version) ;
                doc.vclock[my_index] = new_version ;
                // sync nodes
                CREATE_QUEUE.put( doc ) ;
            }
	    } catch (Exception e) {
	    	throw new DocumentException( e.toString() ) ;
	    }
    }


    public static String get_document(String key) throws DocumentException {
    	System.out.println( "Get Document: " + key ) ;
    	Document doc = KEYMAP_CACHE.get( key ) ;
    	if ( doc == null || doc.record == null || doc.json == null )
    		throw new DocumentException( "Document Not Found: " + key ) ;
    	String record_key = doc.record ;
    	SM db = SMFactory.getInstance() ;
    	SM.OID record_id ;
        SM.Record found ;
		record_id = db.getOID( record_key.getBytes() ) ;
        try {
            found = db.fetch( record_id ) ;
            byte[] bytes = found.getBytes() ;
            String jsonText = new String(bytes) ;
            System.out.println( "Document Found: " + key ) ;    
            return jsonText ;
        } catch (SM.NotFoundException nfe) {
        	System.out.println( "Document Found: " + key ) ;    
			throw new DocumentException( "Document Not Found: " + key ) ;   
		} catch (Exception e) {
			throw new DocumentException( e.toString() ) ;                 
        }   	
    }


    public static SyncRequest get_sync_request(String key) throws DocumentException {
        System.out.println( "Get Document: " + key ) ;
        Document doc = KEYMAP_CACHE.get( key ) ;
        if ( doc == null || doc.record == null )
            throw new DocumentException( "Document Not Found: " + key ) ;
        String record_key = doc.record ;
        SM db = SMFactory.getInstance() ;
        SM.OID record_id ;
        SM.Record found ;
        record_id = db.getOID( record_key.getBytes() ) ;
        try {
            found = db.fetch( record_id ) ;
            byte[] bytes = found.getBytes() ;
            String jsonText = new String(bytes) ;
            System.out.println( "Document Found: " + key ) ;    
            SyncRequest sync = new SyncRequest() ;
            sync.key = doc.key ;
            sync.json = jsonText ;
            sync.vclock = copy_clock(doc.vclock) ; //deep copy????????????????????
            sync.command = "" ; // set by caller
            return sync ;
        } catch (SM.NotFoundException nfe) {
            System.out.println( "Document Found: " + key ) ;    
            throw new DocumentException( "Document Not Found: " + key ) ;   
        } catch (Exception e) {
            throw new DocumentException( e.toString() ) ;                 
        }       
    }


    public static void update_document( String key, String value ) throws DocumentException {
    	System.out.println( "Update Document: " + key ) ;
    	Document doc = KEYMAP_CACHE.get( key ) ;
    	if ( doc == null || doc.record == null )
    		throw new DocumentException( "Document Not Found: " + key ) ;
    	String record_key = doc.record ;
    	SM db = SMFactory.getInstance() ;
        SM.Record found ;
        SM.Record record ;
        SM.OID update_id ;        
		SM.OID record_id = db.getOID( record_key.getBytes() ) ;
		String jsonText = value ;
		int size = jsonText.getBytes().length ;
 		try {
            // store json to db
            record = new SM.Record( size ) ;
            record.setBytes( jsonText.getBytes() ) ;
            update_id = db.update( record_id, record ) ;
            System.out.println( "Document Updated: " + key ) ;
            // update vclock
            AdminServer server = AdminServer.getInstance() ;
            String my_host = server.getMyHostname() ;
            doc.vclock[0] = my_host ;
            int my_index = server.nodeIndex( my_host ) ;
            String old_version = doc.vclock[my_index] ;
            String[] splits = old_version.split(":") ;
            int version = Integer.parseInt(splits[1])+1 ;
            String new_version = my_host + ":" + Integer.toString(version) ;            
            doc.vclock[my_index] = new_version ;
            // sync nodes
            AdminServer.syncDocument( key, "update" ) ; 
			return ;             
        } catch (SM.NotFoundException nfe) {
			throw new DocumentException( "Document Not Found: " + key ) ;
       	} catch (Exception e) {
           	throw new DocumentException( e.toString() ) ;           
        }
    }


    public static void delete_document( String key ) throws DocumentException {
    	System.out.println( "Delete Document: " + key ) ;
    	Document doc = KEYMAP_CACHE.get( key ) ;
    	if ( doc == null || doc.record == null )
    		throw new DocumentException( "Document Not Found: " + key ) ;
    	String record_key = doc.record ;
    	SM db = SMFactory.getInstance() ;
        SM.Record found ;
        SM.Record record ;     
		SM.OID record_id = db.getOID( record_key.getBytes() ) ;
       	try {
            //db.delete( record_id ) ; //could move after or not do anything instead (comment out)
            // sync node
            doc.json = null ;
            // update vclock
            AdminServer server = AdminServer.getInstance() ;
            String my_host = server.getMyHostname() ;
            doc.vclock[0] = my_host ;
            int my_index = server.nodeIndex( my_host ) ;
            String old_version = doc.vclock[my_index] ;
            String[] splits = old_version.split(":") ;
            int version = Integer.parseInt(splits[1])+1 ;
            String new_version = my_host + ":" + Integer.toString(version) ;
            doc.vclock[my_index] = new_version ;
            System.out.println( "Document Deleted: " + key ) ;

            AdminServer.syncDocument( key, "delete" ) ;
            // remove key map
            //KEYMAP_CACHE.remove( key ) ;

        } catch (Exception e) {
         	throw new DocumentException( e.toString() ) ;            
        }		
    }



}


