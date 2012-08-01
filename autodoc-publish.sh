#!/bin/bash

rm -rf autodoc/
git clone git@github.com:RedHatQE/katello.auto.git autodoc

cd autodoc
git symbolic-ref HEAD refs/heads/gh-pages
rm .git/index
git clean -fdx
cd ..

lein autodoc 

cd autodoc
git add -A
git commit -m "Documentation update"
git push origin gh-pages
