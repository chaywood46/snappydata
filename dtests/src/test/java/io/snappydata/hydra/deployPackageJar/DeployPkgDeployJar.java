package io.snappydata.hydra.deployPackageJar;

import hydra.Log;
import io.snappydata.hydra.cluster.SnappyTest;
import util.TestException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 *   class : DeployPkgDeployJar.java
 *   Creation Date : 24th September, 2018
 *   CopyRight : SnappyData Inc.
 *   Revision History
 *   Version 1.0  - 09/24/2018
 * </p>
 * <b>DeployPkgDeployJar.java</b> class, test the below commands.
 * <p>
 * <br> 1. deploy package <unique-alias-name> [repos ‘repositories’] [path 'some path to cache resolved jars'].
 * <br> 2. list packages or list jars.
 * <br> 3. undeploy [jar-name].</br>
 *      4. deploy jar <unique-alias-name> 'jars';
 * </p>
 *
 * <p>
 * In this class we are using Maven Central repositories and local machine path as path to resolve the cache.
 * </p>
 * @author <a href="mailto:cbhatt@snappydata.io">Chandresh Bhatt</a>
 * @since 1.0.2
 *
 */
public class DeployPkgDeployJar extends SnappyTest {

    private static DeployPkgDeployJar deployPkgJars;
    //TODO Below Maps has hard coded value need to be replaced with Command Line Argument in future.
    private Map<String, String> deployPkgAliasNameRepository = new LinkedHashMap<>();
    private Map<String, String> deployJarAliasNameJarName = new LinkedHashMap<>();

    //Below Map stores the Name and Coordinates which is used for deployment.
    static private Map<String, String> source_Alias_Coordinate = new ConcurrentHashMap<>();

    //Below Map stores the result of 'list packages;' or 'list jars;' commands.
    static private Map<String, String> result_Alias_Coordinate = new ConcurrentHashMap<>();

    static private short testInvalidCharacterPkg = 0;
    static private short testInvalidCharacterJar = 0;
    private String userHomeDir = null;


    public DeployPkgDeployJar() {
        if (deployPkgAliasNameRepository.isEmpty()) {
            deployPkgAliasNameRepository.put("sparkavro_integration", "com.databricks:spark-avro_2.11:4.0.0");
            deployPkgAliasNameRepository.put("redshiftdatasource", "com.databricks:spark-redshift_2.10:3.0.0-preview1");
            deployPkgAliasNameRepository.put("mongodbdatasource", "com.stratio.datasource:spark-mongodb_2.11:0.12.0");
            deployPkgAliasNameRepository.put("spark_kafka_lowlevelintegration", "com.tresata:spark-kafka_2.10:0.6.0");
        }

        if (deployJarAliasNameJarName.isEmpty()) {
            deployJarAliasNameJarName.put("sparkmongodb", "spark-mongodb_2.11-0.12.0.jar");
            deployJarAliasNameJarName.put("redshift", "spark-redshift-2.0.0.jar");
        }

        if(userHomeDir == null)
            userHomeDir = System.getProperty("user.home");
    }

    /**
     * This method call the deployPackages() method and local cache is used to store the repository locally.
     * It is a Hydra Task.
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployPackageUsingJDBC_LocalCache() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployPackages();
    }

    /**
     * This method call the deployJar() method and local cache is used to store the jar path locally.
     * It is a Hydra Task.
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployJarUsingJDBC_LocalCache() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployJars();
    }

    /**
     * This method use the bad package name (violate the rule, use characters other than alphabets, number and underscore).
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployPackage_InvalidName() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployPackagesWithInvalidName();
        testInvalidCharacterPkg++;
    }

    /**
     * This method use the bad jar name (violate the rule, use characters other than alphabets, number and underscore).
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployJar_InvalidName() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployJarWithInvalidName();
        testInvalidCharacterJar++;
    }

    /**
     * This method use the deploy package command with multiple packages (comma delimited packages).
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployCommaDelimitedPkgs_CommaDelimitedJars() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployCommaDelimitedPkgs_CommaDelimitedJars();
    }

    /**
     * This method use the deploy package command with multiple packages (space delimited packages).
     *
     * @since 1.0.2
     */
    public static void HydraTask_deploySpaceDelimitedPkgs() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deploySpaceDelimitedPkgs();
    }

    /**
     * This method use the deploy jar command with multiple packages (space delimited jars).
     *
     * @since 1.0.2
     */
    public static void HydraTask_deploySpaceDelimitedJars() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deploySpaceDelimitedJars();
    }


    /**
     * This method use the undeloy command with
     *
     * @since 1.0.2
     */
    public static void HydraTask_undeloyAll() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.undeloyAll();
    }

    //FIXME  -- It is Fixed (8th Oct,2018) :
    //In running cluster when we deployed the packages and jars and if we do not stop the cluster and if we try to deploy the packages again with ivy2 as local cache
    //below exception occurs :
    //snappy-sql> deploy package sparkavro_integration 'com.databricks:spark-avro_2.11:4.0.0' path '/home/cbhatt/TPC';
    //snappy-sql> deploy package sparkavro_integration 'com.databricks:spark-avro_2.11:4.0.0';
    //ERROR 38000: (SQLState=38000 Severity=20000) (Server=localhost/127.0.0.1[1528] Thread=ThriftProcessor-0) The exception 'com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException: myID: 127.0.0.1(8012)<v1>:22584, caused by java.lang.IllegalArgumentException: requirement failed: File com.databricks_spark-avro_2.11-4.0.0.jar was already registered with a different path (old path = /home/cbhatt/TPC/jars/com.databricks_spark-avro_2.11-4.0.0.jar, new path = /home/cbhatt/.ivy2/jars/com.databricks_spark-avro_2.11-4.0.0.jar' was thrown while evaluating an expression.
    //Hence at the starting we need to do the cleanup, stop the cluster and then executes the below test cases.

    /**
     * This method call the deployPackages() method and ivy2 is used to store the repository locally.
     * It is a Hydra Task.
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployPackageUsingJDBC_ivy2() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployPackages_ivy2();
    }

    /**
     * This method test that, if user do not provide unique-alias name
     * then Snappy should produce the error.
     * unique-alias-name specified in context 'of deploying jars/packages' is not unique.
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployPackageUniqueAliasName() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployPackageUniqueAliasName();
    }

    /**
     * This method test that, if user do not provide unique-alias name
     * then Snappy should produce the error.
     * unique-alias-name specified in context 'of deploying jars/packages' is not unique.
     *
     * @since 1.0.2
     */
    public static void HydraTask_deployJarUniqueAliasName() {
        deployPkgJars = new DeployPkgDeployJar();
        deployPkgJars.deployJarUniqueAliasName();
    }



    /**
     * This method establish the connection with Snappy SQL via JDBC.
     *
     * @throws SQLException if did not get connection with Snappy Locator.
     */
    private Connection getJDBCConnection() {
        Connection connection;
        try {
            connection = getLocatorConnection();
        } catch (SQLException se) {
            throw new TestException("Got exception while establish the connection.....", se);
        }
        return connection;
    }

    /**
     * This method validate the unique-alias-name and Maven co-ordinates provided while deploy the jar.
     *
     * @since 1.0.2
     */
    private void validate_Deployed_Artifacts(Map<String, String> resultMap, Map<String, String> sourceMap) {
        if (resultMap.equals(sourceMap))
            Log.getLogWriter().info("Result alias and coordinates are same as Source alias and coordinates.....");
        else
            Log.getLogWriter().info("Result alias and coordinates are not same as Source alias and coordinates.....");
    }

    /**
     * This method clear all the instance variables, static variables.
     *
     * @since 1.0.2
     */
    private void clearAll() {
        Log.getLogWriter().info("ClearAll()");
        if(!source_Alias_Coordinate.isEmpty())
            source_Alias_Coordinate.clear();
        if(!result_Alias_Coordinate.isEmpty())
            result_Alias_Coordinate.clear();
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package, list package and then undeploy any two packages
     * and again executes the list packages will list the remaining packages.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void deployPackages() {
        Connection conn;
        String deploy_Pkg_Cmd;
        final String list_Pkg_Cmd = "list packages;";
        final String path = " path ";
        final String local_repository_path = userHomeDir + "/TPC";
        String undeploy_Pkg_Cmd;
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try {
            for (Map.Entry<String, String> entry : deployPkgAliasNameRepository.entrySet()) {
                deploy_Pkg_Cmd = "deploy package " + entry.getKey() + " '" + entry.getValue() + "'" + path + "'" + local_repository_path + "';";
                Log.getLogWriter().info("Executing deploy package command : " + deploy_Pkg_Cmd);
                source_Alias_Coordinate.put(entry.getKey().toLowerCase(), entry.getValue().toLowerCase());
                conn.createStatement().execute(deploy_Pkg_Cmd);
            }

            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                    result_Alias_Coordinate.put(rs.getString("alias").toLowerCase(), rs.getString("coordinate").toLowerCase());
                }
            }

            validate_Deployed_Artifacts(result_Alias_Coordinate, source_Alias_Coordinate);

            st.clearBatch();

            int count = 0;
            for (Map.Entry<String, String> entry : source_Alias_Coordinate.entrySet()) {
                undeploy_Pkg_Cmd = "undeploy " + entry.getKey() + ";";
                Log.getLogWriter().info("Executing undeploy package command : " + undeploy_Pkg_Cmd);
                conn.createStatement().execute(undeploy_Pkg_Cmd);
                source_Alias_Coordinate.remove(entry.getKey());
                result_Alias_Coordinate.remove(entry.getKey());
                count++;
                if (count >= 2)
                    break;
            }

            st.clearBatch();

            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                Log.getLogWriter().info("Rest of the packages are after undeploy command execution : ");
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                }
            }
        } catch (SQLException se) {
            throw new TestException(se.getMessage() + ", Deploy Package Exception with local repository.....");
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy jar, list jars and then undeploy any one jar.
     * and again executes the list jars will list the remaining jars.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void deployJars() {
        Connection conn;
        String deploy_Jar_Cmd;
        final String list_Jar_Cmd = "list jars;";
        final String local_repository_path = userHomeDir + "/Downloads/Jars/";
        String undeploy_Pkg_Cmd;
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try {
            for (Map.Entry<String, String> entry : deployJarAliasNameJarName.entrySet()) {
                deploy_Jar_Cmd = "deploy jar " + entry.getKey() + " '" + local_repository_path + entry.getValue() + "';";
                Log.getLogWriter().info("Executing deploy jar command : " + deploy_Jar_Cmd);
                source_Alias_Coordinate.put(entry.getKey().toLowerCase(), entry.getValue().toLowerCase());
                conn.createStatement().execute(deploy_Jar_Cmd);
            }

            //TODO 'list jars;' command will show the previous packages as well hence result is mismatch while doing validation since source for jar deployment is different.
            Log.getLogWriter().info("Executing list jars command : " + list_Jar_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Jar_Cmd)) {
                rs = st.getResultSet();
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                    result_Alias_Coordinate.put(rs.getString("alias").toLowerCase(), rs.getString("coordinate").toLowerCase());
                }
            }

            validate_Deployed_Artifacts(result_Alias_Coordinate, source_Alias_Coordinate);

            st.clearBatch();

            int count = 0;
            for (Map.Entry<String, String> entry : source_Alias_Coordinate.entrySet()) {
                undeploy_Pkg_Cmd = "undeploy " + entry.getKey() + ";";
                Log.getLogWriter().info("Executing undeploy package command : " + undeploy_Pkg_Cmd);
                conn.createStatement().execute(undeploy_Pkg_Cmd);
                source_Alias_Coordinate.remove(entry.getKey());
                result_Alias_Coordinate.remove(entry.getKey());
                count++;
                if (count >= 1)
                    break;
            }

            st.clearBatch();

            Log.getLogWriter().info("Executing list packages command : " + list_Jar_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Jar_Cmd)) {
                rs = st.getResultSet();
                Log.getLogWriter().info("Rest of the jars are after undeploy command execution : ");
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                }
            }
        }
        catch (SQLException se)
        {
            throw new TestException(se.getMessage() + ", Deploy Jar Exception with local repository.....");
        }
        finally {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package command and use the special character(characters other than alphabets, numbers and underscore).
     * Method should catch the exception Invalid Name.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void deployPackagesWithInvalidName()
    {
        Connection conn;
        String deploy_Pkg_Cmd;
        final String path = " path ";
        final String local_repository_path = userHomeDir + "/TPC";
        String name = null;
        String coordinate = null;

        if(testInvalidCharacterPkg == 0) {
            name = "sparkavro#integration";
            coordinate = "com.databricks:spark-avro_2.11:4.0.0";
        }
        if(testInvalidCharacterPkg == 1) {
            name = "redshift>datasource";
            coordinate = "com.databricks:spark-redshift_2.10:3.0.0-preview1";
        }
        if(testInvalidCharacterPkg == 2) {
            name = "mongodb?datasource";
            coordinate = "com.stratio.datasource:spark-mongodb_2.11:0.12.0";
        }
        if(testInvalidCharacterPkg == 3) {
            name = "spark+kafka_lowlevelintegration";
            coordinate = "com.tresata:spark-kafka_2.10:0.6.0";
            testInvalidCharacterPkg = 0;
        }

        conn = getJDBCConnection();
        try
        {
            deploy_Pkg_Cmd = "deploy package " + name + " '" + coordinate + "'" + path + "'" + local_repository_path + "';";
            Log.getLogWriter().info("Executing deploy package command : " + deploy_Pkg_Cmd);
            conn.createStatement().execute(deploy_Pkg_Cmd);
        }
        catch(SQLException se)
        {
            Log.getLogWriter().info("Invalid Characters encounter in package name other than Alphabets, Numbers or Underscore.....");
        }
         finally
        {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy jar command and use the special character(characters other than alphabets, numbers and underscore).
     * Method should catch the exception Invalid Name.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void deployJarWithInvalidName()
    {
        Connection conn;
        String deploy_jar_Cmd;
        String name = null;
        String coordinate = null;

        if(testInvalidCharacterJar == 0) {
            name = "SparkMongoDB**";
            coordinate = userHomeDir + "/Downloads/Jars/spark-mongodb_2.11-0.12.0.jar;";
        }
        if(testInvalidCharacterJar == 1) {
            name = "Red$Shift";
            coordinate = userHomeDir + "/Downloads/Jars/spark-redshift-2.0.0.jar;";
            testInvalidCharacterJar = 0;
        }

        conn = getJDBCConnection();
        try
        {
            deploy_jar_Cmd = "deploy jar " + name + " '" + coordinate + "';";
            Log.getLogWriter().info("Executing deploy jar command : " + deploy_jar_Cmd);
            conn.createStatement().execute(deploy_jar_Cmd);
        }
        catch(SQLException se)
        {
            Log.getLogWriter().info("Invalid Characters encounter in jar name other than Alphabets, Numbers or Underscore.....");
        }
        finally
        {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package command with multiple packages.
     * User can provide multiple package with comma delimiter.
     * This method uses the local cache (local repository path).
     *
     * @since 1.0.2
     */
    private void deployCommaDelimitedPkgs_CommaDelimitedJars()
    {
        Connection conn;
        String deploy_Pkg_Cmd;
        String deploy_Jar_Cmd;
        final String list_Pkg_Cmd = "list packages;";
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try
        {
            deploy_Pkg_Cmd = "deploy package GraphPkgs 'tdebatty:spark-knn-graphs:0.13,webgeist:spark-centrality:0.11,SherlockYang:spark-cc:0.1' path "  + "'"+ userHomeDir + "/TPC';";
            Log.getLogWriter().info("Executing deploy package command : " + deploy_Pkg_Cmd);
            conn.createStatement().execute(deploy_Pkg_Cmd);

            deploy_Jar_Cmd = "deploy jar SerDe '" + userHomeDir + "/Downloads/Jars/hive-serde-0.8.0.jar," + userHomeDir + "/Downloads/Jars/hive-json-serde.jar';";
            Log.getLogWriter().info("Executing deploy jar command : " + deploy_Jar_Cmd);
            conn.createStatement().execute(deploy_Jar_Cmd);

            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                }
            }

            conn.createStatement().execute("undeploy GraphPkgs;");
            conn.createStatement().execute("undeploy SerDe;");
        }
        catch(SQLException se)
        {
            throw new TestException(se.getMessage() + ", Exception in Comma Delimiter Package deployment.....");
        }
        finally
        {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package command with multiple packages.
     * User can provide multiple package with space delimiter. Expected output is method should throw the exception. (Wrong Delimiter used).
     * This method uses the local cache (local repository path).
     */
    private void deploySpaceDelimitedPkgs()
    {
        Connection conn;
        String deploy_Pkg_Cmd;

        conn = getJDBCConnection();
        try
        {
            deploy_Pkg_Cmd = "deploy package GraphPkgs 'tdebatty:spark-knn-graphs:0.13 webgeist:spark-centrality:0.11,SherlockYang:spark-cc:0.1' path "  + "'"+ userHomeDir + "/TPC';";
            Log.getLogWriter().info("Executing deploy package command : " + deploy_Pkg_Cmd);
            conn.createStatement().execute(deploy_Pkg_Cmd);
        }
        catch(SQLException se)
        {
            Log.getLogWriter().info("deploy package command is not comma delimited.....");
        }
        finally
        {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy jar command with multiple packages.
     * User can provide multiple package with space delimiter. Expected output is method should throw the exception. (Wrong Delimiter used).
     * This method uses the local cache (local repository path).
     */
    private void deploySpaceDelimitedJars()
    {
        Connection conn;
        String deploy_Jar_Cmd;

        conn = getJDBCConnection();
        try
        {
            deploy_Jar_Cmd = "deploy jar SerDe '" + userHomeDir + "/Downloads/Jars/hive-serde-0.8.0.jar " + userHomeDir + "/Downloads/Jars/hive-json-serde.jar';";
            Log.getLogWriter().info("Executing deploy jar command : " + deploy_Jar_Cmd);
            conn.createStatement().execute(deploy_Jar_Cmd);
        }
        catch(SQLException se)
        {
            Log.getLogWriter().info("deploy jar command is not comma delimited.....");
        }
        finally
        {
            closeConnection(conn);
        }
    }

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package, list package and then undeploy any two packages
     * and again executes the list packages will list the remaining packages.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void undeloyAll() {
        Connection conn;
        final String list_Pkg_Cmd = "list packages;";
        String undeploy_Pkg_Cmd;
        Statement st;
        ResultSet rs;

        Log.getLogWriter().info(".....CLOSING TASK.....");

        if(!deployPkgAliasNameRepository.isEmpty())
            deployPkgAliasNameRepository.clear();
        if(!deployJarAliasNameJarName.isEmpty())
            deployJarAliasNameJarName.clear();

        conn = getJDBCConnection();

        try {
            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                    result_Alias_Coordinate.put(rs.getString("alias").toLowerCase(), rs.getString("coordinate").toLowerCase());
                }
            }


            st.clearBatch();

            for (Map.Entry<String, String> entry : result_Alias_Coordinate.entrySet()) {
                undeploy_Pkg_Cmd = "undeploy " + entry.getKey() + ";";
                Log.getLogWriter().info("Executing undeploy package command : " + undeploy_Pkg_Cmd);
                conn.createStatement().execute(undeploy_Pkg_Cmd);
                result_Alias_Coordinate.remove(entry.getKey());
            }

            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                Log.getLogWriter().info("Rest of the packages are after undeploy command execution : ");
                while (!rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                    Log.getLogWriter().info("No deploy package or deploy jar found.....");
                }
            }
        } catch (SQLException se) {
            throw new TestException(se.getMessage() + ", Exception in clean up .....");
        } finally {
            closeConnection(conn);
            clearAll();
        }
    }



    //FIXME : -- Fixed (8th Oct, 2018)
    //In running cluster when we deployed the packages and jars and if we do not stop the cluster and if we try to deploy the packages again with ivy2 as local cache
    //below exception occurs :
    //snappy-sql> deploy package sparkavro_integration 'com.databricks:spark-avro_2.11:4.0.0' path '/home/cbhatt/TPC';
    //snappy-sql> deploy package sparkavro_integration 'com.databricks:spark-avro_2.11:4.0.0';
    //ERROR 38000: (SQLState=38000 Severity=20000) (Server=localhost/127.0.0.1[1528] Thread=ThriftProcessor-0) The exception 'com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException: myID: 127.0.0.1(8012)<v1>:22584, caused by java.lang.IllegalArgumentException: requirement failed: File com.databricks_spark-avro_2.11-4.0.0.jar was already registered with a different path (old path = /home/cbhatt/TPC/jars/com.databricks_spark-avro_2.11-4.0.0.jar, new path = /home/cbhatt/.ivy2/jars/com.databricks_spark-avro_2.11-4.0.0.jar' was thrown while evaluating an expression.
    //Hence at the starting we need to do the cleanup, stop the cluster and then executes the below test cases.

    /**
     * Establish the connection with Snappy Cluster using JDBC.
     * Fire the deploy package, list package and then undeploy any two packages
     * and again executes the list packages will list the remaining packages.
     * In this method local cache (local repository path) is used.
     *
     * @since 1.0.2
     */
    private void deployPackages_ivy2() {
        Connection conn;
        String deployPkgCmd;
        final String list_Pkg_Cmd = "list packages;";
        String undeployPkgCmd;
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try {
            deployPkgCmd = "deploy package pySpark 'TargetHolding:pyspark-cassandra:0.3.5,com.ryft:spark-ryft-connector_2.10:0.9.0';";
            Log.getLogWriter().info("Executing deploy package command : " + deployPkgCmd);
            conn.createStatement().execute(deployPkgCmd);
            Log.getLogWriter().info("Executing list packages command : " + list_Pkg_Cmd);
            st = conn.createStatement();
            if (st.execute(list_Pkg_Cmd)) {
                rs = st.getResultSet();
                while (rs.next()) {
                    Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                    if(rs.getString("alias").equals("pySpark".toUpperCase()) && (rs.getString("coordinate").equals("TargetHolding:pyspark-cassandra:0.3.5,com.ryft:spark-ryft-connector_2.10:0.9.0")))
                    {
                        Log.getLogWriter().info("list packages match with deployed packages.");
                    }
                    else
                        throw new TestException("list packages do not match with deployed packages using ivy2 cache.");
                }
            }
            st.clearBatch();
            undeployPkgCmd = "undeploy pySpark;";
            Log.getLogWriter().info("Executing undeploy package command : " + undeployPkgCmd);
            conn.createStatement().execute(undeployPkgCmd);
        } catch (SQLException se) {
            throw new TestException(se.getMessage() + ", ivy2 - Deploy Package Exception with local repository");
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * This method test that, deploy package command must use unique-alias-name for Package.
     * If not used then snappy should produce the error.
     * This is the bad test case.
     * @since 1.0.2
     */
    private void deployPackageUniqueAliasName() {
        Connection conn;
        String deployPkgCmd;
        final String listPkgCmd = "list packages;";
        final String path = " path ";
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try {
            Log.getLogWriter().info("Executing list packages command : " + listPkgCmd);
            deployPkgCmd = "deploy package SparkXMLLib 'com.databricks:spark-xml_2.11:0.4.1'" +  path  +  "'"+ userHomeDir + "/TPC';";
            Log.getLogWriter().info("Deploy Package with Unique Alias Name : " + deployPkgCmd);
            conn.createStatement().execute(deployPkgCmd);
            deployPkgCmd = "deploy package SparkXMLLib 'com.databricks:spark-avro_2.11:4.0.0'" +  path  +  "'"+ userHomeDir + "/TPC';";
            Log.getLogWriter().info("Deploy Package with Unique Alias Name : " + deployPkgCmd);
            conn.createStatement().execute(deployPkgCmd);
        }
        catch(Exception e) {
            Log.getLogWriter().info("Exception in Unique Alias Name (deploy package) Module : " + e.getMessage());
            try{
                conn.createStatement().execute("undeploy SparkXMLLib;");
                Log.getLogWriter().info("Executing list packages command again: " + listPkgCmd);
                st = conn.createStatement();
                if(st.execute(listPkgCmd)) {
                    rs = st.getResultSet();
                    if(rs.next())
                        Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                }
            }catch (SQLException se) {
                Log.getLogWriter().info(se.getMessage());
            }
        }
        finally {
            closeConnection(conn);
        }
    }

    /**
     * This method test that, deploy jar command must use unique-alias-name for Jar.
     * If not used then snappy should produce the error.
     * This is the bad test case.
     * @since 1.0.2
     */
    private void deployJarUniqueAliasName() {
        Connection conn;
        String deployJarCmd;
        final String listPkgCmd = "list packages;";
        Statement st;
        ResultSet rs;

        conn = getJDBCConnection();
        try {
            Log.getLogWriter().info("Executing list packages command : " + listPkgCmd);
            deployJarCmd = "deploy jar XMLSerDe "  + "'" + userHomeDir + "/Downloads/Jars/hive-serde-0.8.0.jar';";
            Log.getLogWriter().info("Deploy Jar with Unique Alias Name : " + deployJarCmd);
            conn.createStatement().execute(deployJarCmd);
            deployJarCmd = "deploy jar XMLSerDe " + "'" + userHomeDir + "/Downloads/Jars/hive-json-serde.jar';";
            Log.getLogWriter().info("Deploy Jar with Unique Alias Name : " + deployJarCmd);
            conn.createStatement().execute(deployJarCmd);
        }
        catch(Exception e) {
            Log.getLogWriter().info("Exception in Unique Alias Name Module (deploy jar) : " + e.getMessage());
            try{
                conn.createStatement().execute("undeploy XMLSerDe;");
                Log.getLogWriter().info("Executing list packages command again: " + listPkgCmd);
                st = conn.createStatement();
                if(st.execute(listPkgCmd)) {
                    rs = st.getResultSet();
                    if(rs.next())
                        Log.getLogWriter().info(rs.getString("alias") + "|" + rs.getString("coordinate") + "|" + rs.getString("isPackage"));
                }
            }catch (SQLException se) {
                Log.getLogWriter().info(se.getMessage());
            }
        }
        finally {
            closeConnection(conn);
        }
    }
}

