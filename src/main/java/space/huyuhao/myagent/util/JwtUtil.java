package space.huyuhao.myagent.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret:mySecretKeyForDemoPurposeOnlyAndShouldBeChangedInProduction}")
    private String secret;

    @Value("${jwt.expiration:36000}") // 默认1小时
    private Long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(Long userId, String username) {
        Date expirationDate = new Date(System.currentTimeMillis() + expiration * 1000);

        return Jwts.builder()
                .claim("userId", userId)
                .subject(username)
                .issuedAt(new Date())
                .expiration(expirationDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims getClaimsFromToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new RuntimeException("Invalid JWT token", e);
        }
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
//        log.info("JWT claims: {}", claims);
        Object rawUserId = claims.get("userId");
//        log.info("raw userId from claims: {} (type: {})", rawUserId,
//                rawUserId != null ? rawUserId.getClass().getName() : "null");
        if (rawUserId instanceof Number) {
            return ((Number) rawUserId).longValue();
        }
        return null;
    }

    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException e) {
            return false;
        }
    }
}