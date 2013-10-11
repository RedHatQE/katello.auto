# Katello.auto GUI automation 

## Usage

There are several options for running automation. The easiest way to run it is to install [Leiningen](https://github.com/technomancy/leiningen/blob/master/README.md) and its [runproject plugin](https://github.com/weissjeffm/lein-runproject)

### Running pre-packaged tests from command line
Run 

    $ lein runproject com.redhat.qe/katello.auto 1.0.0-SNAPSHOT -h

to get a list of command line options.

An example command line would be 

    $ lein runproject com.redhat.qe/katello.auto 1.0.0-SNAPSHOT -s https://my.host/katello katello.tests.suite/katello-tests  katello.tests.suite

where the last argument is the test
group to run.  See [suite file](https://github.com/RedHatQE/katello.auto/blob/master/src/katello/tests/suite.clj) for list of available groups.

By default this will start up several firefox browsers and run the specified
tests in parallel.

### Viewing results

After the tests finish running, there will be a file testng-report.xml in the current directory.  You can either view this file directly in an editor or browser, or if you prefer a nicely formatted HTML report, [Jenkins](http://jenkins-ci.org/) with the TestNG plugin can generate one for you.  The internal Red Hat QE Jenkins server has a [job set up to display the result](https://url.corp.redhat.com/e82371c) - just upload the xml file. 

If you are running your own Jenkins server, this job is very easy to set up.  Just add a file parameter `testng-report.xml`, and then check the box `Publish TestNG Results` and fill in `*.xml`.
