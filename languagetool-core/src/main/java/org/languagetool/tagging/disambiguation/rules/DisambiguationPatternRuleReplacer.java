/* LanguageTool, a natural language style checker
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * Copyright (C) 2013 Stefan Lotties
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.tagging.disambiguation.rules;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.patterns.*;
import org.languagetool.tools.StringTools;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @since 2.3
 */
class DisambiguationPatternRuleReplacer extends AbstractPatternRulePerformer {

  DisambiguationPatternRuleReplacer(DisambiguationPatternRule rule) {
    super(rule, rule.getLanguage().getDisambiguationUnifier());
  }

  private void doMatch2(List<PatternTokenMatcher> patternTokenMatchers, AnalyzedTokenReadings[] tokens, MatchConsumer2 consumer) throws IOException {
    int[] tokenPositions = new int[patternTokenMatchers.size()];
    int patternSize = patternTokenMatchers.size();
    int limit = Math.max(0, tokens.length - patternSize + 1);
    PatternTokenMatcher pTokenMatcher = null;
    int i = 0;
    int minOccurCorrection = getMinOccurrenceCorrection();
    while (i < limit + minOccurCorrection && !(rule.isSentStart() && i > 0)) {
      int skipShiftTotal = 0;
      boolean allElementsMatch = false;
      unifiedTokens = null;
      int matchingTokens = 0;
      int firstMatchToken = -1;
      int lastMatchToken = -1;
      int firstMarkerMatchToken = -1;
      int lastMarkerMatchToken = -1;
      int prevSkipNext = 0;
      if (rule.isTestUnification()) {
        unifier.reset();
      }
      int minOccurSkip = 0;
      for (int k = 0; k < patternSize; k++) {
        PatternTokenMatcher prevTokenMatcher = pTokenMatcher;
        pTokenMatcher = patternTokenMatchers.get(k);
        pTokenMatcher.resolveReference(firstMatchToken, tokens, rule.getLanguage());
        int nextPos = i + k + skipShiftTotal - minOccurSkip;
        prevMatched = false;
        if (prevSkipNext + nextPos >= tokens.length || prevSkipNext < 0) { // SENT_END?
          prevSkipNext = tokens.length - (nextPos + 1);
        }
        int maxTok = Math.min(nextPos + prevSkipNext, tokens.length - (patternSize - k) + minOccurCorrection);
        for (int m = nextPos; m <= maxTok; m++) {
          allElementsMatch = testAllReadings(tokens, pTokenMatcher, prevTokenMatcher, m, firstMatchToken, prevSkipNext);

          if (pTokenMatcher.getPatternToken().getMinOccurrence() == 0) {
            boolean foundNext = false;
            for (int k2 = k + 1; k2 < patternSize; k2++) {
              PatternTokenMatcher nextElement = patternTokenMatchers.get(k2);
              boolean nextElementMatch = testAllReadings(tokens, nextElement, pTokenMatcher, m,
                firstMatchToken, prevSkipNext);
              if (nextElementMatch) {
                // this element doesn't match, but it's optional so accept this and continue
                allElementsMatch = true;
                minOccurSkip++;
                tokenPositions[matchingTokens++] = 0;
                foundNext = true;
                break;
              } else if (nextElement.getPatternToken().getMinOccurrence() > 0) {
                break;
              }
            }
            if (foundNext) {
              break;
            }
          }

          if (allElementsMatch) {
            int skipForMax = skipMaxTokens(tokens, pTokenMatcher, firstMatchToken, prevSkipNext,
              prevTokenMatcher, m, patternSize - k - 1);
            lastMatchToken = m + skipForMax;
            int skipShift = lastMatchToken - nextPos;
            tokenPositions[matchingTokens++] = skipShift + 1;
            prevSkipNext = pTokenMatcher.getPatternToken().getSkipNext();
            skipShiftTotal += skipShift;
            if (firstMatchToken == -1) {
              firstMatchToken = lastMatchToken - skipForMax;
            }
            if (firstMarkerMatchToken == -1 && pTokenMatcher.getPatternToken().isInsideMarker()) {
              firstMarkerMatchToken = lastMatchToken - skipForMax;
            }
            if (pTokenMatcher.getPatternToken().isInsideMarker()) {
              lastMarkerMatchToken = lastMatchToken;
            }
            break;
          }
        }
        if (!allElementsMatch) {
          break;
        }
      }
      if (allElementsMatch && matchingTokens == patternSize) {
        consumer.consume(tokenPositions, matchingTokens, firstMatchToken, lastMatchToken, lastMarkerMatchToken);
      }
      i++;
    }
  }

  public final AnalyzedSentence replace(AnalyzedSentence sentence)
      throws IOException {
    List<PatternTokenMatcher> patternTokenMatchers = createElementMatchers();

    AnalyzedTokenReadings[] tokens = sentence.getTokensWithoutWhitespace();
    AnalyzedTokenReadings[] preDisambigTokens = sentence.getTokens();
    AnalyzedTokenReadings[][] whTokens = {sentence.getTokens()};
    boolean[] changed = {false};

    doMatch2(patternTokenMatchers, tokens, (tokenPositions, matchingTokens, firstMatchToken, lastMatchToken, lastMarkerMatchToken) -> {
      int ruleMatchFromPos = -1;
      int ruleMatchToPos = -1;
      int tokenCount = 0;
      for (AnalyzedTokenReadings token : tokens) {
        if (ruleMatchFromPos == -1 && tokenCount == firstMatchToken) {
          ruleMatchFromPos = token.getStartPos();
        }
        if (ruleMatchToPos == -1 && tokenCount == lastMatchToken) {
          ruleMatchToPos = token.getEndPos();
        }
        tokenCount++;
      }
      matchingTokens -= Arrays.stream(tokenPositions).filter(i -> i == 0).count();
      if (keepDespiteFilter(tokens, tokenPositions, firstMatchToken, lastMatchToken) && keepByDisambig(sentence, ruleMatchFromPos, ruleMatchToPos)) {
        whTokens[0] = executeAction(sentence, whTokens[0], unifiedTokens, firstMatchToken, lastMarkerMatchToken, matchingTokens, tokenPositions);
        changed[0] = true;
      }
    });
    if (changed[0]) {
      return new AnalyzedSentence(whTokens[0], preDisambigTokens);
    }
    return sentence;
  }

  private interface MatchConsumer2 {
    void consume(int[] tokenPositions, int matchingTokens, int firstMatchToken, int lastMatchToken, int lastMarkerMatchToken) throws IOException;
  }

  private boolean keepByDisambig(AnalyzedSentence sentence, int ruleMatchFromPos, int ruleMatchToPos) throws IOException {
    List<DisambiguationPatternRule> antiPatterns = rule.getAntiPatterns();
    for (DisambiguationPatternRule antiPattern : antiPatterns) {
      PatternRule disambigRule = new PatternRule("fake-disambig-id", rule.getLanguage(), antiPattern.getPatternTokens(), "desc", "msg", "short");
      RuleMatch[] matches = disambigRule.match(sentence);
      if (matches != null) {
        for (RuleMatch disMatch : matches) {
          if ((disMatch.getFromPos() <= ruleMatchFromPos && disMatch.getToPos() >= ruleMatchFromPos) ||  // left overlap of rule match start
              (disMatch.getFromPos() <= ruleMatchToPos && disMatch.getToPos() >= ruleMatchToPos) ||  // right overlap of rule match end
              (disMatch.getFromPos() >= ruleMatchFromPos && disMatch.getToPos() <= ruleMatchToPos)  // inside longer rule match
          ) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean keepDespiteFilter(AnalyzedTokenReadings[] tokens, int[] tokenPositions, int firstMatchToken, int lastMatchToken) throws IOException {
    RuleFilter filter = rule.getFilter();
    if (filter != null) {
      RuleFilterEvaluator ruleFilterEval = new RuleFilterEvaluator(filter);
      List<Integer> tokensPos = IntStream.of(tokenPositions).boxed().collect(Collectors.toList());
      Map<String, String> resolvedArguments = ruleFilterEval.getResolvedArguments(rule.getFilterArguments(), tokens, firstMatchToken, tokensPos);
      AnalyzedTokenReadings[] relevantTokens = Arrays.copyOfRange(tokens, firstMatchToken, lastMatchToken + 1);
      return filter.matches(resolvedArguments, relevantTokens, firstMatchToken);
    }
    return true;
  }

  private AnalyzedTokenReadings[] executeAction(AnalyzedSentence sentence,
                                                AnalyzedTokenReadings[] whiteTokens,
                                                AnalyzedTokenReadings[] unifiedTokens,
                                                int firstMatchToken, int lastMatchToken,
                                                int matchingTokens, int[] tokenPositions) {
    AnalyzedTokenReadings[] whTokens = whiteTokens.clone();
    DisambiguationPatternRule rule = (DisambiguationPatternRule) this.rule;

    int correctedStPos = 0;
    int startPositionCorrection = rule.getStartPositionCorrection();
    int endPositionCorrection = rule.getEndPositionCorrection();

    int matchingTokensWithCorrection = matchingTokens;

    if (startPositionCorrection > 0) {
      correctedStPos--; //token positions are shifted by 1

      for (int l = 0; l <= startPositionCorrection && l < tokenPositions.length; l++) {
        correctedStPos += tokenPositions[l];
      }

      int w = startPositionCorrection; // adjust to make sure the token count is fine as it's checked later
      for (int j = 0; j <= w; j++) {
        if (j < tokenPositions.length && tokenPositions[j] == 0) {
          startPositionCorrection--;
        }
      }
    }

    if (endPositionCorrection < 0) { // adjust the end position correction if one of the elements has not been matched
      for (int d = startPositionCorrection; d < tokenPositions.length; d++) {
        if (tokenPositions[d] == 0) {
          endPositionCorrection++;
        }
      }
    }

    if (lastMatchToken != -1) {
      int maxPosCorrection = Math.max((lastMatchToken + 1 - (firstMatchToken + correctedStPos)) - matchingTokens, 0);
      matchingTokensWithCorrection += maxPosCorrection;
    }

    int fromPos = sentence.getOriginalPosition(firstMatchToken + correctedStPos);

    DisambiguationPatternRule.DisambiguatorAction disAction = rule.getAction();

    AnalyzedToken[] newTokenReadings = rule.getNewTokenReadings();
    Match matchElement = rule.getMatchElement();
    String disambiguatedPOS = rule.getDisambiguatedPOS();

    switch (disAction) {
    case UNIFY:
      if (unifiedTokens != null &&
          unifiedTokens.length == matchingTokensWithCorrection - startPositionCorrection + endPositionCorrection) {
        //TODO: unifiedTokens.length is larger > matchingTokensWithCorrection in cases where there are no markers...
        if (whTokens[sentence.getOriginalPosition(firstMatchToken
            + correctedStPos + unifiedTokens.length - 1)].isSentenceEnd()) {
          unifiedTokens[unifiedTokens.length - 1].setSentEnd();
        }
        for (int i = 0; i < unifiedTokens.length; i++) {
          int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos + i);
          whTokens[position] = new AnalyzedTokenReadings(whTokens[position], unifiedTokens[i].getReadings(), rule.getFullId());
        }
      }
      break;
    case REMOVE:
      if (newTokenReadings != null && newTokenReadings.length > 0) {
        if (newTokenReadings.length == matchingTokensWithCorrection
            - startPositionCorrection + endPositionCorrection) {
          for (int i = 0; i < newTokenReadings.length; i++) {
            int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos + i);
            whTokens[position].removeReading(newTokenReadings[i], rule.getFullId());
          }
        }
      } else if (!StringTools.isEmpty(disambiguatedPOS)) { // negative filtering
        Pattern p = Pattern.compile(disambiguatedPOS);
        AnalyzedTokenReadings tmp = new AnalyzedTokenReadings(whTokens[fromPos].getReadings(),
            whTokens[fromPos].getStartPos());
        for (AnalyzedToken analyzedToken : tmp) {
          if (analyzedToken.getPOSTag() != null && p.matcher(analyzedToken.getPOSTag()).matches()) {
            int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos);
            whTokens[position].removeReading(analyzedToken, rule.getFullId());
          }
        }
      }
      break;
    case ADD:
      if (newTokenReadings != null && newTokenReadings.length == matchingTokensWithCorrection
            - startPositionCorrection + endPositionCorrection) {
        for (int i = 0; i < newTokenReadings.length; i++) {
          String token;
          int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos + i);
          if (newTokenReadings[i].getToken().isEmpty()) {
            token = whTokens[position].getToken();
          } else {
            token = newTokenReadings[i].getToken();
          }
          String lemma;
          if (newTokenReadings[i].getLemma() == null) {
            lemma = token;
          } else {
            lemma = newTokenReadings[i].getLemma();
          }
          AnalyzedToken newTok = new AnalyzedToken(token,
              newTokenReadings[i].getPOSTag(), lemma);
          whTokens[position].addReading(newTok, rule.getFullId());
        }
      }
      break;
    case FILTERALL:
      for (int i = 0; i < matchingTokensWithCorrection - startPositionCorrection + endPositionCorrection; i++) {
        int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos + i);
        PatternToken pToken;
        if (tokenPositions[i + startPositionCorrection] > 0) {
          pToken = rule.getPatternTokens().get(i + startPositionCorrection);
        } else {
          int k = 1;
          while (i + startPositionCorrection + k < rule.getPatternTokens().size() + endPositionCorrection &&
              tokenPositions[i + startPositionCorrection + k] == 0) {
            k++;
          }
         pToken = rule.getPatternTokens().get(i + k + startPositionCorrection);
        }
        Match tmpMatchToken = new Match(pToken.getPOStag(), null,
            true,
            pToken.getPOStag(),
            null, Match.CaseConversion.NONE, false, false,
            Match.IncludeRange.NONE);

        MatchState matchState = tmpMatchToken.createState(rule.getLanguage().getSynthesizer(), whTokens[position]);
        whTokens[position] = new AnalyzedTokenReadings(whTokens[position], matchState.filterReadings().getReadings(), rule.getFullId());
      }
      break;
    case IMMUNIZE:
      for (int i = 0; i < matchingTokensWithCorrection - startPositionCorrection + endPositionCorrection; i++) {
        whTokens[sentence.getOriginalPosition(firstMatchToken + correctedStPos + i)].immunize();
      }
      break;
    case IGNORE_SPELLING:
      for (int i = 0; i < matchingTokensWithCorrection - startPositionCorrection + endPositionCorrection; i++) {
        whTokens[sentence.getOriginalPosition(firstMatchToken + correctedStPos + i)].ignoreSpelling();
      }
      break;
    case FILTER:
      if (matchElement == null) { // same as REPLACE if using <match>
        Match tmpMatchToken = new Match(disambiguatedPOS, null,
            true, disambiguatedPOS, null,
            Match.CaseConversion.NONE, false, false,
            Match.IncludeRange.NONE);
        boolean newPOSmatches = false;

        // only apply filter rule when it matches previous tags:
        for (int i = 0; i < whTokens[fromPos].getReadingsLength(); i++) {
          if (!whTokens[fromPos].getAnalyzedToken(i).hasNoTag() &&
              whTokens[fromPos].getAnalyzedToken(i).getPOSTag() != null &&
              whTokens[fromPos].getAnalyzedToken(i).getPOSTag().matches(disambiguatedPOS)) {
            newPOSmatches = true;
            break;
          }
        }
        if (newPOSmatches) {
          MatchState matchState = tmpMatchToken.createState(rule.getLanguage().getSynthesizer(), whTokens[fromPos]);
          whTokens[fromPos] = new AnalyzedTokenReadings(whTokens[fromPos], matchState.filterReadings().getReadings(), rule.getFullId());
          
        }
        break;
      }
      //fallthrough
    case REPLACE:
    default:
        if (newTokenReadings != null && newTokenReadings.length > 0) {
          if (newTokenReadings.length == matchingTokensWithCorrection - startPositionCorrection + endPositionCorrection) {
            for (int i = 0; i < newTokenReadings.length; i++) {
              String token;
              int position = sentence.getOriginalPosition(firstMatchToken + correctedStPos + i);
              if ("".equals(newTokenReadings[i].getToken())) { // empty token
                token = whTokens[position].getToken();
              } else {
                token = newTokenReadings[i].getToken();
              }
              String lemma;
              if (newTokenReadings[i].getLemma() == null) { // empty lemma
                lemma = token;
              } else {
                lemma = newTokenReadings[i].getLemma();
              }
              AnalyzedToken analyzedToken = new AnalyzedToken(token, newTokenReadings[i].getPOSTag(), lemma);
              AnalyzedTokenReadings toReplace = new AnalyzedTokenReadings(
                  analyzedToken,
                  whTokens[fromPos].getStartPos());
              whTokens[position] = new AnalyzedTokenReadings(whTokens[position], toReplace.getReadings(), rule.getFullId());
            }
          }
        } else if (matchElement == null) {
          String lemma = "";
          for (AnalyzedToken analyzedToken : whTokens[fromPos]) {
            if (analyzedToken.getPOSTag() != null
                && analyzedToken.getPOSTag().equals(disambiguatedPOS) && analyzedToken.getLemma() != null) {
              lemma = analyzedToken.getLemma();
            }
          }
          if (StringTools.isEmpty(lemma)) {
            lemma = whTokens[fromPos].getAnalyzedToken(0).getLemma();
          }

          AnalyzedToken analyzedToken = new AnalyzedToken(whTokens[fromPos].getToken(), disambiguatedPOS, lemma);
          AnalyzedTokenReadings toReplace = new AnalyzedTokenReadings(
              analyzedToken, whTokens[fromPos].getStartPos());
          whTokens[fromPos] = new AnalyzedTokenReadings(whTokens[fromPos], toReplace.getReadings(), rule.getFullId());
        } else {
          // using the match element
          MatchState matchElementState = matchElement.createState(rule.getLanguage().getSynthesizer(), whTokens[fromPos]);
          whTokens[fromPos] = new AnalyzedTokenReadings(whTokens[fromPos], matchElementState.filterReadings().getReadings(), rule.getFullId());
          matchElementState.filterReadings();
        }
      }

    return whTokens;
  }

}
