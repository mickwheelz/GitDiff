import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class Main {

    public static Map<String, String> fileToFolderMap = new HashMap<>();
    public static Map<String, String> folderToMetadataTypeMap = new HashMap<>();
    public static Set<String> metadataTypeSet = new HashSet<>();
    public static String tempRepoDir = "tempRepo/";
    //public static String srcDir = "src/";



    public static void main(String[] args) throws Exception {
        run(args);
    }

    private static void run(String[] args) throws Exception {

        if(args.length != 6) {
            invalidArgs();
        }
        else {
            String buildPath = args[0];
            String repoURL = args[1];
            String version = args[2];
            String definitionPath = args[3];
            String srcDir = args[4];
            String gitBranch = args[5];

            String relDir = "release/" + gitBranch + "/";

            //remove unneeded data
            setupFiles(buildPath, repoURL);

            //get list of changed files in last commit
            ArrayList<String> files = getDiffFiles(getRepo(repoURL, gitBranch));

            //create maps for XML
            createMaps(files, definitionPath, srcDir);

            //setup build directory and copy changed files
            createFolders(files, buildPath);
            copyFilesToBuildDirectoru(files, buildPath);

            //System.out.println(folderToMetadataTypeMap);
            //build package xml
            buildPackageXML(version, buildPath, srcDir);
            //build maifest text file
            buildMaifest(files, buildPath, relDir, srcDir);
        }

    }

    private static void invalidArgs() {

        System.out.println("Invalid arguements");
        System.out.println("Usage: sfdc-git-diff <Build Path> <Repository URL> <SF API Version> <Definition File> <Source Directory>");
        System.out.println();
        System.out.println("Build path: The folder to store the metadata and pakage XML for deployment");
        System.out.println("Repository URL: the url to the repository, can be local or remote");
        System.out.println("SF API Version: Version of the salesforce API to use, must also match the salesforce deployment tool (ANT) version installed");
        System.out.println("Definition File: location of the file containing the Metadata to folder type definition");
        System.out.println("Source Directory: The folder to within the repository that contains the salesforce metadata");
        System.out.println();
        System.out.println("Example: sfdc-git-diff build/ https://github.com/user/repo.git 38.0 definition.json src/");

    }

    //GIT METHODS

    private static Repository getRepo(String repoURL, String gitBranch) throws Exception{

        Git git;
        if(repoURL.contains("http:") || repoURL.contains("https://")) {
             git = Git.cloneRepository().setURI(repoURL).setDirectory(new File(tempRepoDir)).setBranch(gitBranch).call();
        }
        else {
            git = Git.cloneRepository().setGitDir(new File(repoURL)).setDirectory(new File(tempRepoDir)).setBranch(gitBranch).call();
        }

        return git.getRepository();

    }

    private static ArrayList<String> getDiffFiles(Repository repo) throws Exception {

        ArrayList<String> diffFiles = new ArrayList<String>();

        /*RevCommit headCommit = getHeadCommit(repo);
        RevCommit diffWith = headCommit.getParent(0);
        FileOutputStream stdout = new FileOutputStream(FileDescriptor.out);
        DiffFormatter diffFormatter = new DiffFormatter(stdout);
        diffFormatter.setRepository(repo);

        ArrayList<String> filePaths = new ArrayList<String>();

        for (DiffEntry entry : diffFormatter.scan(diffWith, headCommit)) {
            filePaths.add(entry.getNewPath());
        }*/

        RevCommit headCommit = getHeadCommit(repo);
        RevTree tree = headCommit.getTree();
        //System.out.println("Having tree: " + tree);
        TreeWalk treeWalk = new TreeWalk(repo);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(false);
        while (treeWalk.next()) {
            if (treeWalk.isSubtree()) {
                treeWalk.enterSubtree();
            } else {
                //System.out.println("file: " + treeWalk.getPathString());
                if(treeWalk.getPathString().contains("/")) {
                    diffFiles.add(treeWalk.getPathString());
                }
            }
        }

        return diffFiles;

        }

    private static RevCommit getHeadCommit(Repository repository) throws Exception {
        Git git = new Git(repository);
        Iterable<RevCommit> history = git.log().setMaxCount(1).call();
        return history.iterator().next();
    }

    //FILE METHODS

    private static void copyFilesToBuildDirectoru(ArrayList<String> filePaths, String buildPath) throws IOException {

        for (String s : filePaths) {
            File f = new File(buildPath + s);
            Files.copy(new File(tempRepoDir + s).toPath(), f.toPath());
        }

    }

    private static void createFolders(ArrayList<String> filePaths, String buildPath) {

        for (String s : filePaths) {
            File f = new File(buildPath + s);
            f.getParentFile().mkdirs();
        }

    }

    public static void recursiveDelete(String directory) throws IOException{
        Path rootPath = Paths.get(directory);

        Files.walk(rootPath, FileVisitOption.FOLLOW_LINKS)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .peek(System.out::println)
                .forEach(File::delete);
    }

    private static void setupFiles(String buildpath, String repoURL) {

        try {
            if(new File(buildpath).exists()) {
                recursiveDelete(buildpath);

            }
            if(new File(tempRepoDir).exists()) {
                recursiveDelete(tempRepoDir);
            }

        }
        catch (IOException e) {
            e.printStackTrace();
        }

    }

    //XML METHODS

    private static void createMaps(ArrayList<String> filePaths, String definitionPath, String srcDir) {

        folderToMetadataTypeMap = folderNameToType(definitionPath);
        for(String fp :filePaths) {

            String apexPath = "";
            try {
                apexPath = fp.split(srcDir)[1];
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            if(apexPath.contains("/") && !apexPath.contains("xml")) {
                System.out.println(apexPath);
                fileToFolderMap.put(apexPath.split("/")[1], folderToMetadataTypeMap.get(apexPath.split("/")[0]));
                metadataTypeSet.add(folderToMetadataTypeMap.get(apexPath.split("/")[0]));

            }
        }

    }

    private static void buildPackageXML(String version, String buildPath,String sourceDir) {

        try {

            Map<String, ArrayList<String>> typeToObjectListMap = new HashMap<String, ArrayList<String>>();

            for(String s :metadataTypeSet) {
                ArrayList<String> objects = new ArrayList<String>();
                for(String ff :fileToFolderMap.keySet()) {
                    if(fileToFolderMap.get(ff) == s) {
                        objects.add(ff.split("\\.")[0]);
                    }
                    typeToObjectListMap.put(s, objects);
                }
            }

            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            Document doc = docBuilder.newDocument();
            Element rootElement = doc.createElement("Package");
            doc.appendChild(rootElement);

            Attr attr = doc.createAttribute("xmlns");
            attr.setValue("http://soap.sforce.com/2006/04/metadata");
            rootElement.setAttributeNode(attr);

            for (String s : metadataTypeSet) {
                if(s != null) {
                    Element types = doc.createElement("types");
                    rootElement.appendChild(types);

                    for (String obj : typeToObjectListMap.get(s)) {

                        System.out.println("Type: " + s + " Obj: " + obj);

                        Element members = doc.createElement("members");
                        members.appendChild(doc.createTextNode(obj));
                        types.appendChild(members);

                    }

                    Element name = doc.createElement("name");
                    name.appendChild(doc.createTextNode(s));

                    types.appendChild(name);
                }
            }

            Element pkgVersion = doc.createElement("version");
            pkgVersion.appendChild(doc.createTextNode(version));
            rootElement.appendChild(pkgVersion);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File( buildPath + sourceDir + "package.xml"));

            transformer.transform(source, result);

            System.out.println("Package XML saved!");

        } catch (ParserConfigurationException pce) {
            pce.printStackTrace();
        } catch (TransformerException tfe) {
            tfe.printStackTrace();
        }
    }

    private static void buildMaifest(ArrayList<String> filePaths, String buildDir, String releasePath, String sourceDir) {

        String manifest = "";

        try {

            for(String fp :filePaths) {
                if(!fp.contains(".xml") && !fp.contains("manifest.txt")) {
                    manifest += fp.split(sourceDir)[1] + "\r\n";
                }
            }

            FileWriter fw = new FileWriter(new File( buildDir + releasePath + "manifest.txt"));
            fw.write(manifest);
            fw.close();

            StreamResult result = new StreamResult();

            System.out.println("Manifest saved!");

        } catch (IOException pce) {
            pce.printStackTrace();
        }

    }

    private static Map<String, String> folderNameToType(String definitionPath) {

        Map<String, String> output = new HashMap<String, String>();

        try {
            Type listType = new TypeToken<ArrayList<FolderToType>>(){}.getType();

            JsonReader reader = new JsonReader(new FileReader(definitionPath));
            ArrayList<FolderToType> listFolderTypes = new Gson().fromJson(reader, listType);

            for(FolderToType ft :listFolderTypes) {
                output.put(ft.getFolderName(), ft.getMetadataType());
                //output.put( ft.getMetadataType(), ft.getFolderName() );

            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return output;

    }


}