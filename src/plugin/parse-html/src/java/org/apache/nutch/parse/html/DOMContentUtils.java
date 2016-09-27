/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.parse.html;

import java.net.URL;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.nutch.parse.Outlink;
import org.apache.nutch.util.NodeWalker;
import org.apache.nutch.util.URLUtil;
import org.apache.hadoop.conf.Configuration;

import org.apache.oro.text.regex.MatchResult;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.PatternMatcherInput;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A collection of methods for extracting content from DOM trees.
 * 
 * This class holds a few utility methods for pulling content out of DOM nodes,
 * such as getOutlinks, getText, etc.
 * 
 */
public class DOMContentUtils {

  public static class LinkParams {
    public String elName;
    public String attrName;
    public int childLen;

    public LinkParams(String elName, String attrName, int childLen) {
      this.elName = elName;
      this.attrName = attrName;
      this.childLen = childLen;
    }

    public String toString() {
      return "LP[el=" + elName + ",attr=" + attrName + ",len=" + childLen + "]";
    }
  }

  public static final Logger  LOG = LoggerFactory.getLogger(DOMContentUtils.class);

  private HashMap<String, LinkParams> linkParams = new HashMap<String, LinkParams>();

  private static final String STRING_PATTERN = "(\\\\*(?:\"|\'))([^\\s\"\']+?)(?:\\1)";
  // A simple pattern. This allows also invalid URL characters.
  private static final String URI_PATTERN = "(^|\\s*?)/?\\S+?[/\\.]\\S+($|\\s*)";

  public DOMContentUtils(Configuration conf) {
    setConf(conf);
  }

  public void setConf(Configuration conf) {
    // forceTags is used to override configurable tag ignoring, later on
    Collection<String> forceTags = new ArrayList<String>(1);

    linkParams.clear();
    linkParams.put("a", new LinkParams("a", "href", 1));
    linkParams.put("area", new LinkParams("area", "href", 0));
    if (conf.getBoolean("parser.html.form.use_action", true)) {
      linkParams.put("form", new LinkParams("form", "action", 1));
      if (conf.get("parser.html.form.use_action") != null)
        forceTags.add("form");
    }
    linkParams.put("frame", new LinkParams("frame", "src", 0));
    linkParams.put("iframe", new LinkParams("iframe", "src", 0));
    linkParams.put("script", new LinkParams("script", "src", 0));
    linkParams.put("link", new LinkParams("link", "href", 0));
    linkParams.put("img", new LinkParams("img", "src", 0));

 //   linkParams.put("script", new LinkParams("script", "data-main", 0));
//  linkParams.put("a", new LinkParams("a", "data-desc", 1));
//    linkParams.put("a", new LinkParams("a", "data-asc", 1));
    // remove unwanted link tags from the linkParams map
    String[] ignoreTags = conf.getStrings("parser.html.outlinks.ignore_tags");
    for (int i = 0; ignoreTags != null && i < ignoreTags.length; i++) {
      if (!forceTags.contains(ignoreTags[i]))
        linkParams.remove(ignoreTags[i]);
    }
  }

  /**
   * This method takes a {@link StringBuilder} and a DOM {@link Node}, and will
   * append all the content text found beneath the DOM node to the
   * <code>StringBuilder</code>.
   * 
   * <p>
   * 
   * If <code>abortOnNestedAnchors</code> is true, DOM traversal will be aborted
   * and the <code>StringBuffer</code> will not contain any text encountered
   * after a nested anchor is found.
   * 
   * <p>
   * 
   * @return true if nested anchors were found
   */
  public boolean getText(StringBuilder sb, Node node,
      boolean abortOnNestedAnchors) {
    if (getTextHelper(sb, node, abortOnNestedAnchors, 0)) {
      return true;
    }
    return false;
  }

  /**
   * This is a convinience method, equivalent to
   * {@link #getText(StringBuffer,Node,boolean) getText(sb, node, false)}.
   * 
   */
  public void getText(StringBuilder sb, Node node) {
    getText(sb, node, false);
  }

  // returns true if abortOnNestedAnchors is true and we find nested
  // anchors
  private boolean getTextHelper(StringBuilder sb, Node node,
      boolean abortOnNestedAnchors, int anchorDepth) {
    boolean abort = false;
    NodeWalker walker = new NodeWalker(node);

    while (walker.hasNext()) {

      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();

      if ("script".equalsIgnoreCase(nodeName)) {
        walker.skipChildren();
      }
      if ("style".equalsIgnoreCase(nodeName)) {
        walker.skipChildren();
      }
      if (abortOnNestedAnchors && "a".equalsIgnoreCase(nodeName)) {
        anchorDepth++;
        if (anchorDepth > 1) {
          abort = true;
          break;
        }
      }
      if (nodeType == Node.COMMENT_NODE) {
        walker.skipChildren();
      }
      if (nodeType == Node.TEXT_NODE) {
        // cleanup and trim the value
        String text = currentNode.getNodeValue();
        text = text.replaceAll("\\s+", " ");
        text = text.trim();
        if (text.length() > 0) {
          if (sb.length() > 0)
            sb.append(' ');
          sb.append(text);
        }
      }
    }

    return abort;
  }

  /**
   * This method takes a {@link StringBuffer} and a DOM {@link Node}, and will
   * append the content text found beneath the first <code>title</code> node to
   * the <code>StringBuffer</code>.
   * 
   * @return true if a title node was found, false otherwise
   */
  public boolean getTitle(StringBuilder sb, Node node) {

    NodeWalker walker = new NodeWalker(node);

    while (walker.hasNext()) {

      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();

      if ("body".equalsIgnoreCase(nodeName)) { // stop after HEAD
        return false;
      }

      if (nodeType == Node.ELEMENT_NODE) {
        if ("title".equalsIgnoreCase(nodeName)) {
          getText(sb, currentNode);
          return true;
        }
      }
    }

    return false;
  }

  /** If Node contains a BASE tag then it's HREF is returned. */
  public URL getBase(Node node) {

    NodeWalker walker = new NodeWalker(node);

    while (walker.hasNext()) {

      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();

      // is this node a BASE tag?
      if (nodeType == Node.ELEMENT_NODE) {

        if ("body".equalsIgnoreCase(nodeName)) { // stop after HEAD
          return null;
        }

        if ("base".equalsIgnoreCase(nodeName)) {
          NamedNodeMap attrs = currentNode.getAttributes();
          for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            if ("href".equalsIgnoreCase(attr.getNodeName())) {
              try {
                return new URL(attr.getNodeValue());
              } catch (MalformedURLException e) {
              }
            }
          }
        }
      }
    }

    // no.
    return null;
  }

  private boolean hasOnlyWhiteSpace(Node node) {
    String val = node.getNodeValue();
    for (int i = 0; i < val.length(); i++) {
      if (!Character.isWhitespace(val.charAt(i)))
        return false;
    }
    return true;
  }

  // this only covers a few cases of empty links that are symptomatic
  // of nekohtml's DOM-fixup process...
  private boolean shouldThrowAwayLink(Node node, NodeList children,
      int childLen, LinkParams params) {
    if (childLen == 0) {
      // this has no inner structure
      if (params.childLen == 0)
        return false;
      else
        return true;
    } else if ((childLen == 1)
        && (children.item(0).getNodeType() == Node.ELEMENT_NODE)
        && (params.elName.equalsIgnoreCase(children.item(0).getNodeName()))) {
      // single nested link
      return true;

    } else if (childLen == 2) {

      Node c0 = children.item(0);
      Node c1 = children.item(1);

      if ((c0.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c0.getNodeName()))
          && (c1.getNodeType() == Node.TEXT_NODE) && hasOnlyWhiteSpace(c1)) {
        // single link followed by whitespace node
        return true;
      }

      if ((c1.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c1.getNodeName()))
          && (c0.getNodeType() == Node.TEXT_NODE) && hasOnlyWhiteSpace(c0)) {
        // whitespace node followed by single link
        return true;
      }

    } else if (childLen == 3) {
      Node c0 = children.item(0);
      Node c1 = children.item(1);
      Node c2 = children.item(2);

      if ((c1.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c1.getNodeName()))
          && (c0.getNodeType() == Node.TEXT_NODE)
          && (c2.getNodeType() == Node.TEXT_NODE) && hasOnlyWhiteSpace(c0)
          && hasOnlyWhiteSpace(c2)) {
        // single link surrounded by whitespace nodes
        return true;
      }
    }

    return false;
  }

  /**
   * This method finds all anchors below the supplied DOM <code>node</code>, and
   * creates appropriate {@link Outlink} records for each (relative to the
   * supplied <code>base</code> URL), and adds them to the <code>outlinks</code>
   * {@link ArrayList}.
   * 
   * <p>
   * 
   * Links without inner structure (tags, text, etc) are discarded, as are links
   * which contain only single nested links and empty text nodes (this is a
   * common DOM-fixup artifact, at least with nekohtml).
   */
  public void getOutlinks(URL base, ArrayList<Outlink> outlinks, Node node) {

    NodeWalker walker = new NodeWalker(node);
    while (walker.hasNext()) {

      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();
      NodeList children = currentNode.getChildNodes();
      int childLen = (children != null) ? children.getLength() : 0;

      if (nodeType == Node.ELEMENT_NODE) {

        nodeName = nodeName.toLowerCase();
        LinkParams params = linkParams.get(nodeName);
        if (params != null) {
          if (!shouldThrowAwayLink(currentNode, children, childLen, params)) {

            StringBuilder linkText = new StringBuilder();
            getText(linkText, currentNode, true);

            NamedNodeMap attrs = currentNode.getAttributes();
            StringBuilder target = new StringBuilder();
            boolean noFollow = false;
            boolean post = false;
            for (int i = 0; i < attrs.getLength(); i++) {
              Node attr = attrs.item(i);
              String attrName = attr.getNodeName();
              if (params.attrName.equalsIgnoreCase(attrName)) {
                target.append(attr.getNodeValue());
              } else if ("rel".equalsIgnoreCase(attrName)
                  && "nofollow".equalsIgnoreCase(attr.getNodeValue())) {
                noFollow = true;
              } else if ("method".equalsIgnoreCase(attrName)
                  && "post".equalsIgnoreCase(attr.getNodeValue())) {
                post = true;
              }

              if (attrName.indexOf("data-") >= 0) {
                  target.append(getLink(attr.getNodeValue(),base.toString()));
              } 
              
              if(attrName.indexOf("data-main") >= 0){
                  target.append(".js");
              }
         
             
            }
            if (target != null && !noFollow && !post)
              try {

                URL url = URLUtil.resolveURL(base, target.toString());
                outlinks.add(new Outlink(url.toString(), linkText.toString()
                    .trim()));
              } catch (MalformedURLException e) {
                // don't care
              }
          }
          // this should not have any children, skip them
          if (params.childLen == 0)
            continue;
        }
      }
    }
  }

  private String getLink(String plainText, String base) {

    String outlink = null;
    URL baseURL = null;

    try {
      baseURL = new URL(base);
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("error assigning base URL", e);
      }
    }

    try {
      final PatternCompiler cp = new Perl5Compiler();
      final Pattern pattern = cp.compile(STRING_PATTERN,
          Perl5Compiler.CASE_INSENSITIVE_MASK | Perl5Compiler.READ_ONLY_MASK
              | Perl5Compiler.MULTILINE_MASK);
      final Pattern pattern1 = cp.compile(URI_PATTERN,
          Perl5Compiler.CASE_INSENSITIVE_MASK | Perl5Compiler.READ_ONLY_MASK
              | Perl5Compiler.MULTILINE_MASK);
      final PatternMatcher matcher = new Perl5Matcher();

      final PatternMatcher matcher1 = new Perl5Matcher();
      final PatternMatcherInput input = new PatternMatcherInput(plainText);

      MatchResult result;
      String url;

      // loop the matches
      while (matcher.contains(input, pattern)) {
        result = matcher.getMatch();
        url = result.group(2);
        PatternMatcherInput input1 = new PatternMatcherInput(url);
        if (!matcher1.matches(input1, pattern1)) {
          if (LOG.isTraceEnabled()) {
            LOG.trace(" - invalid '" + url + "'");
          }
          continue;
        }
        if (url.startsWith("www.")) {
          url = "http://" + url;
        } else {
          // See if candidate URL is parseable. If not, pass and move on to
          // the next match.
          try {
            url = new URL(baseURL, url).toString();
          } catch (MalformedURLException ex) {
            if (LOG.isTraceEnabled()) {
              LOG.trace(" - failed URL parse '" + url + "' and baseURL '"
                  + baseURL + "'", ex);
            }
            continue;
          }
        }
        url = url.replaceAll("&amp;", "&");
        if (LOG.isTraceEnabled()) {
          LOG.trace(" - outlink from JS: '" + url + "'");
        }
        outlink = url;
      }
    } catch (Exception ex) {
      // if it is a malformed URL we just throw it away and continue with
      // extraction.
      if (LOG.isErrorEnabled()) {
        LOG.error(" - invalid or malformed URL", ex);
      }
    }

//    final Outlink[] retval;

    // create array of the Outlinks
//    if (outlinks != null && outlinks.size() > 0) {
//      retval = outlinks.toArray(new Outlink[0]);
//    } else {
//      retval = new Outlink[0];
//    }

    return outlink;
  }
}

